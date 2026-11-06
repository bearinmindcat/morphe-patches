package org.ungoogled.patches.samsungonehand.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal fun Instruction.isDialogInit(): Boolean {
    val ref = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return opcode == Opcode.INVOKE_DIRECT && ref.definingClass == "Landroid/app/Dialog;" && ref.name == "<init>" &&
        ref.parameterTypes.map { it.toString() } == listOf("Landroid/content/Context;")
}

internal val IPUT = setOf(
    Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR,
    Opcode.IPUT_SHORT,
)

/**
 * Replaces the app's reflection helper calls -- helper.call(target, "name", types, args) -- whose method name ends
 * with [suffix] by [target](helper, target, name, types, args), same registers. Returns how many it replaced.
 */
internal fun MutableMethod.redirectReflectiveCalls(suffix: String, target: String): Int {
    val code = implementation!!.instructions.toList()
    val constants = mutableMapOf<Int, String>()
    var replaced = 0
    for ((index, insn) in code.withIndex()) {
        val ref = (insn as? ReferenceInstruction)?.reference
        if (insn.opcode == Opcode.CONST_STRING) {
            constants[(insn as OneRegisterInstruction).registerA] = (ref as StringReference).string
            continue
        }
        if (insn.opcode.setsRegister() && insn is OneRegisterInstruction) constants.remove(insn.registerA)
        val method = ref as? MethodReference ?: continue
        if (insn.opcode != Opcode.INVOKE_VIRTUAL || method.parameterTypes.size != 4 ||
            method.parameterTypes[1] != "Ljava/lang/String;"
        ) continue
        val five = insn as FiveRegisterInstruction
        if (constants[five.registerE]?.endsWith(suffix) != true) continue
        replaceInstruction(
            index,
            "invoke-static { v${five.registerC}, v${five.registerD}, v${five.registerE}, v${five.registerF}, v${five.registerG} }, " +
                "$target(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;",
        )
        replaced++
    }
    return replaced
}
