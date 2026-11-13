package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val VIDEO_EDITS = "Lorg/ungoogled/samsunggallery/VideoEdits;"
/** Gallery's command for a video's Edit off One UI (Samsung's Editor Lite), unobfuscated. */
private const val TRIMMER_COMMAND = "Lcom/samsung/android/gallery/app/controller/externals/StartVideoTrimmerCmd;"
private const val MEDIA_ITEM = "Lcom/samsung/android/gallery/module/data/MediaItem;"
private const val CONTENT_URI = "Lcom/samsung/android/gallery/module/data/ContentUri;"
private const val BASE_COMMAND = "Lcom/samsung/android/gallery/app/controller/BaseCommand;"
private const val GRANT = "Landroid/content/Context;->grantUriPermission(Ljava/lang/String;Landroid/net/Uri;I)V"
private const val ADD_FLAGS = "Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;"

/**
 * A video's Edit opens Samsung's Editor Lite. Without it Gallery only logged the missing app (Edit did nothing); the
 * video now goes to the phone's own video editors, or a message says there is none. With it Gallery crashed: it
 * grants the editor write access to the video, which Gallery itself does not have off One UI. It grants read access
 * (the editor saves its cut as a new video).
 */
internal val videoEditPatch = bytecodePatch(
    description = "Opens a video editor from a video's Edit.",
) {
    extendWith(SAMSUNG_GALLERY_EXTENSION)

    execute {
        val command = mutableClassDefByOrNull(TRIMMER_COMMAND) ?: throw PatchException("Samsung Gallery has no $TRIMMER_COMMAND")
        val launches = command.methods.any { m ->
            m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                    "com.samsung.app.newtrim.trimactivity.TrimActivity"
            } == true
        }
        if (!launches) throw PatchException("$TRIMMER_COMMAND no longer opens Editor Lite")

        val start = command.methods.firstOrNull {
            it.returnType == "Z" && it.parameterTypes == listOf(MEDIA_ITEM) &&
                it.implementation?.instructions?.any { i -> i.opcode == Opcode.MOVE_EXCEPTION } == true
        } ?: throw PatchException("no start method (MediaItem -> boolean with a catch) in $TRIMMER_COMMAND")
        start.addInstructionsWithLabels(
            0,
            """
                invoke-virtual { p0 }, $BASE_COMMAND->getActivity()Landroid/app/Activity;
                move-result-object v0
                invoke-static { p1 }, $CONTENT_URI->getUri(Lcom/samsung/android/gallery/database/dbtype/FileItemInterface;)Landroid/net/Uri;
                move-result-object v1
                invoke-static { v0, v1 }, $VIDEO_EDITS->openElsewhere(Landroid/app/Activity;Landroid/net/Uri;)Z
                move-result v0
                if-eqz v0, :launch
                return v0
            """,
            ExternalLabel("launch", start.implementation!!.instructions.first()),
        )

        // createIntent: flags 3 (read + write) go on the intent and into grantUriPermission; read only.
        val createIntent = command.methods.firstOrNull {
            it.returnType == "Landroid/content/Intent;" && it.parameterTypes == listOf(MEDIA_ITEM)
        } ?: throw PatchException("no createIntent (MediaItem -> Intent) in $TRIMMER_COMMAND")
        val instructions = createIntent.implementation!!.instructions.toList()
        val modes = instructions.indices.filter { i ->
            val insn = instructions[i]
            if (insn.opcode != Opcode.CONST_4 || (insn as NarrowLiteralInstruction).narrowLiteral != 3) return@filter false
            val next = instructions.getOrNull(i + 1) as? FiveRegisterInstruction ?: return@filter false
            ((next as ReferenceInstruction).reference as? MethodReference)?.toString() == ADD_FLAGS &&
                next.registerD == (insn as OneRegisterInstruction).registerA
        }
        val mode = modes.singleOrNull() ?: throw PatchException("${modes.size} read+write grants in $TRIMMER_COMMAND, expected 1")
        val register = (instructions[mode] as OneRegisterInstruction).registerA
        val grant = instructions.drop(mode).any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == GRANT }
        if (!grant) throw PatchException("the grant flags no longer reach grantUriPermission in $TRIMMER_COMMAND")
        createIntent.replaceInstruction(mode, "const/4 v$register, 0x1")
    }
}
