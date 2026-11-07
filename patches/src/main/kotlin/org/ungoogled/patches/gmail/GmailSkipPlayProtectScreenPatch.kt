package org.ungoogled.patches.gmail

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL

/** The two timing metrics of Google's device-compliance verdict, one per branch of what Gmail does with it. */
private const val NOT_COMPLIANT = "android/gms_compliance_device_not_compliant"
private const val COMPLIANT = "android/gms_compliance_device_compliant"

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * Gmail asks Google whether to enforce against this phone (gmscompliance's GetEnforcement, at start and on every new
 * screen). On "enforce" it cancelled every notification, disabled all of its own services, receivers and providers,
 * and opened "This device isn't Play Protect certified" while finishing the screen it was on -- the app was dead until
 * Google said otherwise. An enforce verdict now goes where the other verdicts go, minus their metric: components an
 * earlier run disabled are switched back on (Gmail restarts once), else the Play Protect screen closes if it is open.
 * The check itself still runs; nothing about the phone is faked or reported differently.
 */
internal val gmailPlayProtectScreenCorePatch = bytecodePatch(
    description = "Keeps Gmail from shutting itself down on Google's \"isn't Play Protect certified\" verdict.",
) {
    execute {
        val found = methodsUsing(setOf(NOT_COMPLIANT, COMPLIANT))
        val callback = found.single(NOT_COMPLIANT, "device-compliance verdict callback")
        if (found.single(COMPLIANT, "device-compliance verdict callback").method != callback.method) {
            throw PatchException("the two device-compliance metrics are no longer in one method")
        }
        val method = mutable(callback)
        val code = method.implementation!!.instructions
        val notCompliant = code.indexOfFirst { it.stringOrNull() == NOT_COMPLIANT }
        val compliant = code.indexOfFirst { it.stringOrNull() == COMPLIANT }

        // The verdict test: `if (!verdict.isEnforce()) goto <compliant branch>`, the last branch before the
        // enforce branch's metric; the enforce branch is what falls through it.
        val test = (notCompliant downTo 2).firstOrNull { i ->
            code[i].opcode == Opcode.IF_EQZ && code[i - 1].opcode == Opcode.MOVE_RESULT &&
                code[i - 2].opcode == Opcode.INVOKE_VIRTUAL && code[i - 2].method()?.let { it.returnType == "Z" && it.parameterTypes.isEmpty() } == true
        } ?: throw PatchException("device-compliance callback no longer tests the verdict before acting on it")
        val target = (code[test] as BuilderOffsetInstruction).target.location.index
        if (target <= notCompliant || target > compliant) throw PatchException("device-compliance verdict test no longer skips the enforce branch")

        // The other branch's "components reset pending?" test, after its metric: the helper's preferences object read
        // from the helper, cast, asked ()Z. The enforce branch reads the same field off the same register (to store
        // "reset pending"), so that register still holds the helper where the jump is made.
        val reset = (compliant until code.size - 2).firstOrNull { i ->
            code[i].opcode == Opcode.IGET_OBJECT && code[i + 1].opcode == Opcode.CHECK_CAST &&
                code[i + 2].opcode == Opcode.INVOKE_VIRTUAL && code[i + 2].method()?.let { it.returnType == "Z" && it.parameterTypes.isEmpty() } == true
        } ?: throw PatchException("device-compliance callback no longer checks for disabled components")
        val prefs = code[reset].field()!!
        val helper = (code[reset] as TwoRegisterInstruction).registerB
        val sameRead = (test + 1 until target).any { i ->
            code[i].opcode == Opcode.IGET_OBJECT && code[i].field() == prefs && (code[i] as TwoRegisterInstruction).registerB == helper
        }
        if (!sameRead) throw PatchException("device-compliance enforce branch no longer reads the helper's preferences from v$helper")

        // First instruction of the enforce branch (a fall-through, no label on it).
        method.addInstructionsWithLabels(test + 1, "goto :ungoogled_reset_check", ExternalLabel("ungoogled_reset_check", code[reset]))
    }
}

@Suppress("unused")
val gmailSkipPlayProtectScreenPatch = bytecodePatch(
    name = "Skip Play Protect screen",
    description = "Keeps Gmail working where Google shows \"This device isn't Play Protect certified\".",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GMAIL)
    dependsOn(gmailPlayProtectScreenCorePatch)
}
