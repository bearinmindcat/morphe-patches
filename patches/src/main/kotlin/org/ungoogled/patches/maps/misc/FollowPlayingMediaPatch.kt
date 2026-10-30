package org.ungoogled.patches.maps.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

private const val PLAYING_MEDIA = "Lorg/ungoogled/ui/PlayingMedia;"
private const val COMPAT_TOKEN = "Landroid/support/v4/media/session/MediaSessionCompat\$Token;"

/**
 * Android lists the active media sessions only to an app with notification access, which only a declared
 * notification listener can be given. A root mount install keeps the stock manifest, so it never has one.
 */
private val mediaListenerManifestPatch = resourcePatch(description = "Declares the listener notification access is given to.") {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("service").apply {
                    setAttribute("android:name", "org.ungoogled.ui.MediaListener")
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
        }
    }
}

@Suppress("unused")
val followPlayingMediaPatch = bytecodePatch(
    name = "Follow playing media",
    description = "Navigation's media controls show what an app is playing, also apps that refuse Maps such as " +
        "Pocket Casts and TIDAL. Needs notification access (Customization), not on root mount installs.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, mediaListenerManifestPatch)

    execute {
        markPatched("playingMediaPatched")

        fun Method.refs() = implementation!!.instructions.toList()
        fun mutable(m: Method) = mutableClassDefBy(m.definingClass).methods.single {
            it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == m.returnType
        }

        // The media browser's connection callback: onConnected wraps the browser's session in Maps' controller,
        // or reports "sessionToken is null"; onConnectionFailed reports the app as unavailable. The settings'
        // Default media app list keeps only the apps whose connection succeeds, the media panel controls the
        // session it hands over.
        val connected = mutableListOf<Method>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            c.methods.filterTo(connected) { m ->
                m.returnType == "V" && m.parameterTypes.isEmpty() && m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "sessionToken is null"
                } == true
            }
        }
        val onConnected = mutable(connected.singleOrNull() ?: throw PatchException("media browser callback: found ${connected.size}"))
        val callback = onConnected.definingClass
        val ins = onConnected.refs()

        // this.browserConnection, its MediaBrowserCompat (Object), and the report "unavailable" both callbacks make.
        val connectionField = ins.firstNotNullOfOrNull { i ->
            ((i as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { i.opcode == Opcode.IGET_OBJECT && it.definingClass == callback }
        } ?: throw PatchException("media browser callback keeps no connection")
        val browserField = ins.firstNotNullOfOrNull { i ->
            ((i as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                i.opcode == Opcode.IGET_OBJECT && it.definingClass == connectionField.type && it.type == "Ljava/lang/Object;"
            }
        } ?: throw PatchException("media browser connection keeps no browser")
        val browserType = ins.firstNotNullOfOrNull { i ->
            ((i as? ReferenceInstruction)?.reference as? TypeReference)?.type?.takeIf { i.opcode == Opcode.CHECK_CAST }
        } ?: throw PatchException("media browser is no longer cast in onConnected")
        val unavailable = ins.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "sessionToken is null" }
            .let { i -> (ins[i + 1] as? ReferenceInstruction)?.reference as? MethodReference }
            ?: throw PatchException("\"sessionToken is null\" is no longer reported right away")

        // 1. Connected: the controller is made from the browser's session; take the app's playing one when that is
        //    another (Jellyfin's Android Auto library has a player of its own, so its idle session showed the
        //    library's suggestions instead of the song playing).
        val controllerInit = ins.indexOfFirst { i ->
            val r = (i as? ReferenceInstruction)?.reference as? MethodReference
            i.opcode == Opcode.INVOKE_DIRECT && r?.name == "<init>" &&
                r.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", COMPAT_TOKEN)
        }
        if (controllerInit < 0) throw PatchException("the media controller is no longer made from the browser's session")
        val controller = ins[controllerInit] as Instruction35c
        val token = controller.registerE
        val made = (controllerInit downTo 0).firstOrNull { i ->
            ins[i].opcode == Opcode.NEW_INSTANCE && (ins[i] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA == controller.registerC
        } ?: throw PatchException("the media controller's new-instance is not in onConnected")
        (made until controllerInit).forEach { i ->
            if (ins[i].opcode != Opcode.NEW_INSTANCE && ins[i].opcode != Opcode.CHECK_CAST) throw PatchException("unexpected ${ins[i].opcode} before the media controller")
            if ((ins[i] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA == token) throw PatchException("session token register reused")
        }
        if (token > 15) throw PatchException("session token in v$token, out of invoke range")
        onConnected.addInstructions(
            made,
            """
                invoke-static { v$token }, $PLAYING_MEDIA->follow(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$token
                check-cast v$token, $COMPAT_TOKEN
            """,
        )

        // 2. Connection failed (Pocket Casts, TIDAL, YouTube Music: "No root for client"): when the app has a
        //    session open, it becomes the browser's and Maps goes on as connected -- the app is listed, the panel
        //    controls it.
        val onFailed = mutableClassDefBy(callback).methods.filter { m ->
            m.name != onConnected.name && m.returnType == "V" && m.parameterTypes.isEmpty() &&
                m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? MethodReference) == unavailable } == true
        }.singleOrNull() ?: throw PatchException("media browser's onConnectionFailed not found")
        val failedImpl = onFailed.implementation!!
        if (failedImpl.registerCount - 1 < 1) throw PatchException("onConnectionFailed has no register to spare")
        onFailed.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $connectionField
                invoke-static { v0 }, $PLAYING_MEDIA->adopt(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :refused
                invoke-virtual { p0 }, $callback->${onConnected.name}()V
                return-void
            """,
            ExternalLabel("refused", failedImpl.instructions[0]),
        )

        // 3. Such a browser never connected, and MediaBrowser.getRoot() throws then: the helper both browsing and
        //    the panel's suggestions start with leaves it alone.
        val roots = mutableListOf<Method>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            c.methods.filterTo(roots) { m ->
                AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.firstOrNull()?.toString() == browserType &&
                    m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                            r.definingClass == "Landroid/media/browse/MediaBrowser;" && r.name == "getRoot"
                        } == true
                    } == true
            }
        }
        val browse = mutable(roots.singleOrNull() ?: throw PatchException("media browsing helper: found ${roots.size}"))
        val browseImpl = browse.implementation!!
        val params = browse.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }.sum()
        if (browseImpl.registerCount - params < 1) throw PatchException("media browsing helper has no register to spare")
        browse.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, $PLAYING_MEDIA->browsable(Ljava/lang/Object;)Z
                move-result v0
                if-nez v0, :browse
                return-void
            """,
            ExternalLabel("browse", browseImpl.instructions[0]),
        )

        // 4. The panel's Browse for such an app opens the app instead (as the browser's own "Open" does).
        val panelBrowse = mutableListOf<Pair<Method, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            if (c.fields.none { it.type == connectionField.type && !AccessFlags.STATIC.isSet(it.accessFlags) }) return@classDefForEach
            val open = c.methods.firstOrNull { m ->
                val strings = m.implementation?.instructions?.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                }.orEmpty()
                "android.intent.action.MAIN" in strings && "android-app://" in strings
            } ?: return@classDefForEach
            c.methods.filter { m ->
                m.returnType == "V" && m.parameterTypes.isEmpty() && m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                        r.definingClass == browse.definingClass && r.name == browse.name && r.parameterTypes == browse.parameterTypes
                    } == true
                } == true
            }.forEach { panelBrowse += it to open }
        }
        // Browse is the one that empties the list it fills (the other call loads suggestions when nothing plays).
        val (browseFound, open) = panelBrowse.singleOrNull() ?: throw PatchException("media panel's Browse: found ${panelBrowse.size}")
        val panel = browseFound.definingClass
        if (open.parameterTypes.isNotEmpty()) throw PatchException("the media app is no longer opened without arguments")
        val panelConnection = classDefBy(panel).fields.first { it.type == connectionField.type && !AccessFlags.STATIC.isSet(it.accessFlags) }
        val browseButton = mutable(browseFound)
        val buttonImpl = browseButton.implementation!!
        if (buttonImpl.registerCount - 1 < 1) throw PatchException("media panel's Browse has no register to spare")
        browseButton.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $panel->${panelConnection.name}:${panelConnection.type}
                iget-object v0, v0, $browserField
                invoke-static { v0 }, $PLAYING_MEDIA->browsable(Ljava/lang/Object;)Z
                move-result v0
                if-nez v0, :browse
                invoke-virtual { p0 }, $panel->${open.name}()${open.returnType}
                return-void
            """,
            ExternalLabel("browse", buttonImpl.instructions[0]),
        )
    }
}
