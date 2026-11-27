package org.ungoogled.patches.waze

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE
import org.w3c.dom.Element

private const val MEDIA_BROWSER = "android.media.browse.MediaBrowserService"
private const val ACTION_INIT = "com.waze.sdk.audio.ACTION_INIT"
private const val MEDIA_FEATURE = "anyMediaApp"
private const val SPOTIFY = "com.spotify.music"

/**
 * Since Android 11 Waze sees only the apps its manifest names: its <queries> lists the audio SDK's broadcast and
 * Spotify, so a music app without the SDK was invisible to it. Ask for every app with a media browser service.
 * Some apps let only trusted controllers browse them, and Android trusts an app whose notification listener the
 * user allowed: declare one (org.ungoogled.waze.MediaAccess). Spotify's session reaches a re-signed Waze's player
 * through a media browser of Waze's own (org.ungoogled.waze.SpotifySession). A root mount install keeps the stock
 * manifest.
 */
private val wazeMediaAppsManifestPatch = resourcePatch(
    description = "Lets Waze see every app with a media browser service.",
) {
    compatibleWith(COMPATIBILITY_WAZE)

    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val queries = (0 until root.childNodes.length).map { root.childNodes.item(it) }
                .filterIsInstance<Element>().firstOrNull { it.tagName == "queries" }
                ?: manifest.createElement("queries").also { root.appendChild(it) }
            queries.appendChild(
                manifest.createElement("intent").apply {
                    appendChild(manifest.createElement("action").apply { setAttribute("android:name", MEDIA_BROWSER) })
                },
            )
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("service").apply {
                    setAttribute("android:name", "org.ungoogled.waze.MediaAccess")
                    setAttribute("android:exported", "true")
                    setAttribute("android:permission", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE")
                    appendChild(
                        manifest.createElement("intent-filter").apply {
                            appendChild(
                                manifest.createElement("action").apply {
                                    setAttribute("android:name", "android.service.notification.NotificationListenerService")
                                },
                            )
                        },
                    )
                },
            )
            application.appendChild(
                manifest.createElement("service").apply {
                    setAttribute("android:name", "org.ungoogled.waze.SpotifySession")
                    setAttribute("android:exported", "false")
                },
            )
        }
    }
}

private fun Method.calls(name: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == name
} == true

private fun ClassDef.names(string: String) = methods.any { m ->
    m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string } == true
}

private fun MethodReference.takes(vararg types: String) = parameterTypes.map(CharSequence::toString) == types.toList()

/** One method of the audio SDK's code (com.waze.sdk, renamed every build) for which [match] holds. */
private fun BytecodePatchContext.sdkMethod(what: String, match: (ClassDef, Method) -> Boolean): Pair<String, Method> {
    val found = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (!c.type.startsWith("Lcom/waze/sdk/")) return@classDefForEach
        for (m in c.methods) if (match(c, m)) found += c.type to m
    }
    return found.singleOrNull() ?: throw PatchException("$what: found ${found.size} methods")
}

/** Makes the extension's [name]() answer [value] (its body is a const-string). */
private fun BytecodePatchContext.answer(name: String, value: String) {
    val getter = mutableClassDefBy(MUSIC_APPS).methods.singleOrNull {
        it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("$MUSIC_APPS->$name() not found")
    val first = getter.implementation!!.instructions.first()
    if (first.opcode != Opcode.CONST_STRING) throw PatchException("$name() no longer starts with const-string")
    getter.replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$value\"")
}

private fun String.javaName() = removePrefix("L").removeSuffix(";").replace('/', '.')

/**
 * Waze's audio player offers only the music apps that carry Waze's audio SDK and are on its server's partner list.
 * Every other media app for Android Auto is added to that list here; Waze calls it installed, and when it is picked
 * its session is opened inside Waze -- what the SDK in a partner app would do -- so Waze's own media browser shows
 * the track, plays, pauses, skips and browses (issues #240, #233). Spotify grants its own route only to Waze signed by
 * Waze: a re-signed Waze takes this route for it too, following Spotify's media session (issue #213).
 */
@Suppress("unused")
val wazeAnyMediaAppPatch = bytecodePatch(
    name = "Any media app",
    description = "Lets Waze's audio player use any music app that works with Android Auto, and Spotify.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    // The extension, the partner list hook, a switch on Waze's Customization screen and the apps' visibility.
    dependsOn(wazeCustomizationPatch, wazeMusicAppsPatch, wazeMediaAppsManifestPatch)

    execute {
        // The audio SDK's start-up (ap): "installed" = the app has a receiver for the SDK's ACTION_INIT broadcast,
        // and picking an app sends it that broadcast with a token, which its SDK answers by connecting to Waze.
        val (picker, receives) = sdkMethod("audio app installed check") { c, m ->
            AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "Z" && m.takes("Landroid/content/Intent;") &&
                m.calls("queryBroadcastReceivers") && c.names(ACTION_INIT)
        }
        val (_, init) = sdkMethod("audio app start") { c, m ->
            c.type == picker && AccessFlags.STATIC.isSet(m.accessFlags) &&
                m.takes("Ljava/lang/String;", "Landroid/content/Intent;") && m.calls("sendBroadcast")
        }
        val (_, pick) = sdkMethod("audio app pick") { c, m ->
            c.type == picker && !AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "Z" &&
                m.takes("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/Runnable;")
        }
        // The audio session manager (bd): connect(Resources, package, token, options, messenger), what Waze's SDK
        // service calls when a partner app connects, and the manager's instance.
        val (sessions, connect) = sdkMethod("audio session connect") { _, m ->
            m.returnType == "Z" && m.takes(
                "Landroid/content/res/Resources;", "Ljava/lang/String;", "Ljava/lang/String;",
                "Landroid/os/Bundle;", "Landroid/os/Messenger;",
            )
        }
        // Waze's players by package (a/j): h() asks its instance for the picked app's player.
        val player = init.returnType
        val players = init.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { r ->
                it.opcode == Opcode.INVOKE_VIRTUAL && r.returnType == player && r.takes("Ljava/lang/String;")
            }
        }.singleOrNull()?.definingClass ?: throw PatchException("audio app players not found")
        val remove = classDefBy(players).methods.singleOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.takes("Ljava/lang/String;")
        } ?: throw PatchException("audio app players: remove not found")
        val instance = classDefBy(sessions).methods.singleOrNull {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == sessions
        } ?: throw PatchException("audio session manager instance not found")

        // Installed: a media app counts as having the SDK's receiver.
        mutableClassDefBy(picker).methods.single { it.name == receives.name && it.takes("Landroid/content/Intent;") }.apply {
            if (implementation!!.registerCount < 2) throw PatchException("audio app installed check has no free register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0 }, $MUSIC_APPS->receives(Landroid/content/Intent;)Z
                    move-result v0
                    if-eqz v0, :waze_receivers
                    return v0
                """,
                ExternalLabel("waze_receivers", implementation!!.instructions.first()),
            )
        }
        // Picked: the extension connects a media app's session as its SDK would.
        mutableClassDefBy(picker).methods.single { it.name == init.name && it.takes("Ljava/lang/String;", "Landroid/content/Intent;") }
            .addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $MUSIC_APPS->started(Ljava/lang/String;Landroid/content/Intent;)V",
            )

        // Spotify: the pick's "is it Spotify" (Spotify's own SDK or App Remote route) asks the extension, so a re-signed
        // Waze takes the media app route for it.
        mutableClassDefBy(picker).methods.single {
            it.name == pick.name && it.takes("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/Runnable;")
        }.apply {
            val code = implementation!!.instructions.toList()
            val name = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SPOTIFY }
            val equals = (code.getOrNull(name + 1) as? ReferenceInstruction)?.reference as? MethodReference
            val result = code.getOrNull(name + 2)
            if (name < 0 || equals?.name != "equals" || equals.definingClass != "Ljava/lang/String;" ||
                result?.opcode != Opcode.MOVE_RESULT
            ) {
                throw PatchException("audio app pick: its Spotify check not found")
            }
            val isSpotify = (result as OneRegisterInstruction).registerA
            addInstructions(
                name + 3,
                """
                    invoke-static/range { v$isSpotify .. v$isSpotify }, $MUSIC_APPS->spotifyRoute(Z)Z
                    move-result v$isSpotify
                """,
            )
        }
        // Waze's player for an app (a media browser to the app's service, made by the players by package): the
        // extension may point Spotify's at Waze's own, which follows Spotify's media session.
        val playerArgs = arrayOf("Landroid/content/Context;", "Ljava/lang/String;", "Landroid/content/ComponentName;")
        val playerInit = classDefBy(players).methods.firstNotNullOfOrNull { m ->
            m.implementation?.instructions?.firstNotNullOfOrNull { i ->
                ((i as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                    i.opcode == Opcode.INVOKE_DIRECT && it.name == "<init>" && it.takes(*playerArgs)
                }
            }
        } ?: throw PatchException("audio app player constructor (Context, package, service) not found")
        mutableClassDefBy(playerInit.definingClass).methods.single { it.name == "<init>" && it.takes(*playerArgs) }.addInstructions(
            0,
            """
                invoke-static/range { p2 .. p3 }, $MUSIC_APPS->browser(Ljava/lang/String;Landroid/content/ComponentName;)Landroid/content/ComponentName;
                move-result-object p3
            """,
        )

        answer("sessionsClass", sessions.javaName())
        answer("sessionsInstance", instance.name)
        answer("sessionsConnect", connect.name)
        answer("playersClass", players.javaName())
        answer("playersRemove", remove.name)
        answer("pickerClass", picker.javaName())
        answer("pickerPick", pick.name)
        declareWazeToggle(MEDIA_FEATURE)
    }
}
