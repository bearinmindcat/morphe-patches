package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val VIRTUAL = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
private val STATIC = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

private fun MethodReference.shape() = "$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Sends every call in the app (not in our extensions) to one of [calls] -- class to "name(params)return" shapes of
 * its methods -- to the same-named static method of [target]: a virtual call's receiver becomes the first parameter,
 * a static call keeps its parameters. Same registers, only the target changes. Returns the calls moved.
 */
internal fun BytecodePatchContext.redirectCalls(target: String, calls: Map<String, Set<String>>): Int {
    fun Any.match(): MethodReference? {
        if (this !is ReferenceInstruction || (opcode !in VIRTUAL && opcode !in STATIC)) return null
        val r = reference as? MethodReference ?: return null
        return if (calls[r.definingClass]?.contains(r.shape()) == true) r else null
    }

    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any { it.match() != null } == true) sites += c.type to m
        }
    }
    var moved = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            val r = insn.match() ?: continue
            val receiver = if (insn.opcode in VIRTUAL) r.definingClass else ""
            val to = "$target->${r.name}($receiver${r.parameterTypes.joinToString("")})${r.returnType}"
            val call = when (insn) {
                is FiveRegisterInstruction ->
                    listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                        .take(insn.registerCount).joinToString(", ", "invoke-static { ", " }, $to") { "v$it" }
                is RegisterRangeInstruction ->
                    "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + insn.registerCount - 1} }, $to"
                else -> throw PatchException("unexpected ${insn.opcode} in $type.${method.name}")
            }
            mutable.replaceInstruction(index, call)
            moved++
        }
    }
    return moved
}
