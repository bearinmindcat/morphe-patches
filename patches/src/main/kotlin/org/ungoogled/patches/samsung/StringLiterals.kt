package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

private val CONST_STRING = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)

private fun Any.literal(): String? =
    if (this is ReferenceInstruction && opcode in CONST_STRING) (reference as StringReference).string else null

/**
 * Rewrites every string literal in the app (not in our extensions) that [change] answers a new value for: const-string
 * instructions and the initial values of static String fields (R8 keeps some constants as fields). Returns how many
 * it rewrote.
 */
internal fun BytecodePatchContext.rewriteStringLiterals(change: (String) -> String?): Int {
    val sites = mutableListOf<Pair<String, Method>>()
    val fieldSites = mutableListOf<Pair<String, String>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (f in c.staticFields) {
            val value = (f.initialValue as? StringEncodedValue)?.value ?: continue
            if (change(value) != null) fieldSites += c.type to f.name
        }
        for (m in c.methods) {
            val hit = m.implementation?.instructions?.any { insn -> insn.literal()?.let(change) != null } ?: false
            if (hit) sites += c.type to m
        }
    }
    var rewritten = 0
    for ((type, name) in fieldSites) {
        val field = mutableClassDefBy(type).fields.first { it.name == name }
        val value = field.initialValue as MutableStringEncodedValue
        value.setValue(change(value.value)!!)
        rewritten++
    }
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            val to = insn.literal()?.let(change) ?: continue
            val escaped = to.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            mutable.replaceInstruction(index, "${if (insn.opcode == Opcode.CONST_STRING_JUMBO) "const-string/jumbo" else "const-string"} v${(insn as OneRegisterInstruction).registerA}, \"$escaped\"")
            rewritten++
        }
    }
    return rewritten
}
