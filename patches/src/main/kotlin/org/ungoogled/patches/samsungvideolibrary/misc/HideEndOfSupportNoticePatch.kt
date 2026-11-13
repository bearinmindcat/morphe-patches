package org.ungoogled.patches.samsungvideolibrary.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsungvideolibrary.SamsungVideoLibraryConstants.COMPATIBILITY_SAMSUNG_VIDEO_LIBRARY

/**
 * Every start of the list showed "Notice on the end of app update support" (the app was discontinued on January 1,
 * 2024), a dialog that Back does not close, until its OK was pressed. The notice's "still to show" setting now reads
 * as already seen.
 */
@Suppress("unused")
val hideEndOfSupportNoticePatch = bytecodePatch(
    name = "Hide end of support notice",
    description = "Removes the notice that Samsung discontinued the app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_VIDEO_LIBRARY)

    execute {
        val method = EndOfSupportNoticeFingerprint.method
        val instructions = method.implementation!!.instructions
        val key = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "show_eos_notice"
        }
        // getBoolean("show_eos_notice", true): its result is the next move-result.
        val result = (key + 1 until instructions.size).firstOrNull { instructions[it].opcode == Opcode.MOVE_RESULT }
            ?: throw PatchException("the notice's setting is not read in onResume")
        method.replaceInstruction(result, "const/4 v${(instructions[result] as OneRegisterInstruction).registerA}, 0x0")
    }
}
