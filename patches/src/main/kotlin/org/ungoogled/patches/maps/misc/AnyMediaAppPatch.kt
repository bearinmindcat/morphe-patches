package org.ungoogled.patches.maps.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

private const val MEDIA_APPS = "Lorg/ungoogled/ui/MediaApps;"
private const val MEDIA_BROWSER = "android.media.browse.MediaBrowserService"
private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

/**
 * Navigation's media controls (Settings > Navigation > Default media app) offer only Maps'
 * partners: the list starts from YouTube Music, Pandora and once Play Music -- Spotify has a
 * connection of its own -- and keeps the installed apps with a media browser service among
 * them. Every other app that works with Android Auto goes into that list first (issue #9).
 */
/**
 * Since Android 11 Maps sees only the apps its manifest names: its <queries> lists the partners alone, so
 * every other media app -- Jellyfin, say (issue #78) -- was invisible to its search for media browser
 * services and never offered. Ask for every app that has one.
 */
private val mediaAppsVisiblePatch = resourcePatch(description = "Lets Maps see every app with a media browser service.") {
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
        }
    }
}

@Suppress("unused")
val anyMediaAppPatch = bytecodePatch(
    name = "Any media app",
    description = "Navigation's Default media app (Settings > Navigation) offers every music and podcast app " +
        "that works with Android Auto, such as Poweramp, also where Google switches media controls off. " +
        "YouTube Music and Spotify only connect to Google's own Maps.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, mediaAppsVisiblePatch)

    execute {
        // The list's builder: the one method naming both YouTube Music and the media browser service.
        val owners = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                val strings = m.implementation?.instructions?.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                }.orEmpty()
                if (YOUTUBE_MUSIC in strings && MEDIA_BROWSER in strings) owners += c.type to m
            }
        }
        val (owner, found) = owners.singleOrNull() ?: throw PatchException("media app list: found ${owners.size} methods")
        val method = mutableClassDefBy(owner).methods.single {
            it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType
        }
        val ins = method.implementation!!.instructions.toList()

        // YouTube Music's entry: new <entry>(package, colour, touch colour), then added to the builder.
        val youtube = ins.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == YOUTUBE_MUSIC }
        val init = (youtube until minOf(youtube + 6, ins.size)).firstOrNull { i ->
            ((ins[i] as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "I", "I")
            } == true
        } ?: throw PatchException("YouTube Music's media app entry is no longer built here")
        val entryType = ((ins[init] as ReferenceInstruction).reference as MethodReference).definingClass
        val add = ins[init + 1] as? Instruction35c
        val addRef = (add as? ReferenceInstruction)?.reference as? MethodReference
        if (add == null || add.opcode != Opcode.INVOKE_VIRTUAL || addRef == null ||
            addRef.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;")
        ) throw PatchException("YouTube Music's entry is no longer added to a list right away")
        val builder = add.registerC

        // Where the builder is made: the last ImmutableList.builder() before, into the same register.
        val made = (init downTo 1).firstOrNull { i ->
            val call = (ins[i - 1] as? ReferenceInstruction)?.reference as? MethodReference
            call?.returnType == addRef.definingClass && call.parameterTypes.isEmpty() &&
                ins[i].opcode == Opcode.MOVE_RESULT_OBJECT && (ins[i] as OneRegisterInstruction).registerA == builder
        } ?: throw PatchException("the media app list's builder is no longer made in the same method")
        if (builder > 15) throw PatchException("media app list builder in v$builder, out of invoke range")

        // Issue #78: in front of the builder Maps asks its server whether media controls exist at all (the same
        // switch navigation's media bar reads); "no" left the list empty whatever was installed. Always yes.
        val gate = (made - 1 downTo 0).firstNotNullOfOrNull { i ->
            ((ins[i] as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                ins[i].opcode == Opcode.INVOKE_VIRTUAL && it.returnType == "Z" && it.parameterTypes.isEmpty()
            }
        } ?: throw PatchException("media controls switch not found in front of the media app list")
        val available = mutableClassDefBy(gate.definingClass).methods.single {
            it.name == gate.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }
        // It has no register but `this`, which it can overwrite on its way out.
        available.addInstructions(0, "const/4 p0, 0x1\nreturn p0")

        method.addInstructions(made + 1, "invoke-static { v$builder }, $MEDIA_APPS->addAll(Ljava/lang/Object;)V")

        // Issue #78: the partners Maps lists only when its server says so join after its list is built.
        val now = method.implementation!!.instructions.toList()
        val build = (made + 2 until now.size).firstOrNull { i ->
            val call = (now[i] as? ReferenceInstruction)?.reference as? MethodReference
            now[i].opcode == Opcode.INVOKE_VIRTUAL && (now[i] as Instruction35c).registerC == builder &&
                call?.returnType == "Lcom/google/common/collect/ImmutableList;" && call.parameterTypes.isEmpty()
        } ?: throw PatchException("the media app list is no longer built in the same method")
        val result = now.getOrNull(build + 1) as? OneRegisterInstruction
        if (result == null || now[build + 1].opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("the built media app list is not kept")
        val list = result.registerA
        if (list > 15) throw PatchException("media app list in v$list, out of invoke range")
        method.addInstructions(
            build + 2,
            """
                invoke-static { v$list }, $MEDIA_APPS->complete(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$list
                check-cast v$list, Lcom/google/common/collect/ImmutableList;
            """,
        )

        // Issue #78: "open the media app" from navigation's media panel starts ACTION_MAIN for the package without a
        // category, which most apps do not answer ("Failed to launch application"). The method is the one building
        // that intent (MAIN + Maps' own referrer); right before it is started, the app's launcher intent replaces it.
        run {
            val opens = mutableListOf<Pair<String, Method>>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                for (m in c.methods) {
                    val strings = m.implementation?.instructions?.mapNotNull {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
                    }.orEmpty()
                    if ("android.intent.action.MAIN" in strings && "android-app://" in strings &&
                        "android.intent.extra.REFERRER" in strings
                    ) opens += c.type to m
                }
            }
            // The generic media apps' panel and Spotify's both open their app this way.
            if (opens.isEmpty() || opens.size > 3) throw PatchException("media app launch: found ${opens.size} methods")
            for ((type, found) in opens) {
            val open = mutableClassDefBy(type).methods.single {
                it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType
            }
            val insns = open.implementation!!.instructions.toList()
            val start = insns.indexOfFirst { insn ->
                val r = (insn as? ReferenceInstruction)?.reference as? MethodReference
                r != null && r.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Landroid/content/Intent;", "I")
            }
            if (start < 0) throw PatchException("the media app is no longer started with (Context, Intent, int)")
            val call = insns[start] as Instruction35c
            val context = call.registerD
            val intent = call.registerE
            if (context > 15 || intent > 15) throw PatchException("media app launch registers out of range")
            open.addInstructions(
                start,
                """
                    invoke-static { v$context, v$intent }, $MEDIA_APPS->launchable(Landroid/content/Context;Landroid/content/Intent;)Landroid/content/Intent;
                    move-result-object v$intent
                """,
            )
            }
        }

        // The extension builds entries and adds them by these names.
        fun answer(name: String, value: String) {
            val getter = mutableClassDefBy(MEDIA_APPS).methods.singleOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
            } ?: throw PatchException("$MEDIA_APPS->$name() not found")
            val first = getter.implementation!!.instructions.first()
            if (first.opcode != Opcode.CONST_STRING) throw PatchException("$name() no longer starts with const-string")
            getter.replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$value\"")
        }
        answer("entryClass", entryType.removePrefix("L").removeSuffix(";").replace('/', '.'))
        answer("addMethod", addRef.name)

        // Issue #78: Maps waits for every listed app's media browser to answer its connection, with no time limit --
        // the Default media app list (also opened by Show media playback controls) loads until all have, the media
        // panel likewise. A browser that never answers (its service binding null, as media3's does when it has no
        // session for a legacy browser) kept the list loading for good. Unanswered after ten seconds counts as refused.
        run {
            val callbacks = mutableListOf<Method>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                c.methods.filterTo(callbacks) { m ->
                    m.returnType == "V" && m.parameterTypes.isEmpty() && m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "sessionToken is null"
                    } == true
                }
            }
            val onConnected = callbacks.singleOrNull() ?: throw PatchException("media browser callback: found ${callbacks.size}")
            val callback = mutableClassDefBy(onConnected.definingClass)
            val code = onConnected.implementation!!.instructions.toList()
            // The connection the callback reports to, and its report "unavailable", which only the failures make.
            val connection = code.firstNotNullOfOrNull { i ->
                ((i as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                    i.opcode == Opcode.IGET_OBJECT && it.definingClass == callback.type
                }
            }?.type ?: throw PatchException("media browser callback keeps no connection")
            val unavailable = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "sessionToken is null" }
                .let { i -> (code[i + 1] as? ReferenceInstruction)?.reference as? MethodReference }
                ?: throw PatchException("\"sessionToken is null\" is no longer reported right away")
            // Connected, failed, suspended: each marks the connection answered.
            val answers = callback.methods.filter { m ->
                m.name != "<init>" && m.returnType == "V" && m.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(m.accessFlags)
            }
            val onFailed = answers.singleOrNull { m ->
                m.name != onConnected.name && m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? MethodReference) == unavailable } == true
            } ?: throw PatchException("media browser's onConnectionFailed not found")
            if (answers.size !in 2..3) throw PatchException("media browser callback has ${answers.size} callbacks")
            answers.forEach { it.addInstruction(0, "invoke-static { p0 }, $MEDIA_APPS->answered(Ljava/lang/Object;)V") }

            // Made right before Maps connects: the extension starts the clock with the callback and its connection.
            val init = callback.methods.singleOrNull { m -> m.name == "<init>" && m.parameterTypes.any { it.toString() == connection } }
                ?: throw PatchException("media browser callback is no longer made with its connection")
            var p = 1
            var connectionParam = -1
            init.parameterTypes.forEach { t ->
                if (t.toString() == connection && connectionParam < 0) connectionParam = p
                p += if (t.toString() == "J" || t.toString() == "D") 2 else 1
            }
            val impl = init.implementation!!
            if (impl.registerCount > 16) throw PatchException("media browser callback's constructor registers out of invoke range")
            // Right after the super constructor, while `this` and the connection are still in their registers.
            val self = impl.registerCount - (p - 1) - 1
            val connectionReg = self + connectionParam
            val superInit = impl.instructions.indexOfFirst { insn ->
                insn.opcode == Opcode.INVOKE_DIRECT && (insn as? Instruction35c)?.registerC == self &&
                    ((insn as ReferenceInstruction).reference as MethodReference).name == "<init>"
            }
            if (superInit < 0) throw PatchException("media browser callback's constructor no longer calls its super constructor")
            if (impl.instructions.take(superInit).any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA in setOf(self, connectionReg) }) {
                throw PatchException("media browser callback's constructor reuses its parameters")
            }
            init.addInstruction(superInit + 1, "invoke-static { p0, p$connectionParam }, $MEDIA_APPS->connecting(Ljava/lang/Object;Ljava/lang/Object;)V")
            answer("failedMethod", onFailed.name)
        }
    }
}
