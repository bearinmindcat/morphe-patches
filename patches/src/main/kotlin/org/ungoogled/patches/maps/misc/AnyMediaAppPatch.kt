package org.ungoogled.patches.maps.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

private const val MEDIA_APPS = "Lorg/ungoogled/ui/MediaApps;"
private const val MEDIA_BROWSER = "android.media.browse.MediaBrowserService"
private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

/**
 * Navigation's media controls (Settings > Navigation > Default media app) offer only Maps'
 * partners: the list starts from YouTube Music, Pandora and once Play Music -- Spotify has a
 * connection of its own -- and keeps the installed apps with a media browser service among
 * them. Every other app that works with Android Auto goes into that list first (issue #9).
 */
@Suppress("unused")
val anyMediaAppPatch = bytecodePatch(
    name = "Any media app",
    description = "Navigation's Default media app (Settings > Navigation) offers every music and podcast app " +
        "that works with Android Auto, such as Poweramp, not only Spotify, YouTube Music and Pandora.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch)

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
        method.addInstructions(made + 1, "invoke-static { v$builder }, $MEDIA_APPS->addAll(Ljava/lang/Object;)V")

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
    }
}
