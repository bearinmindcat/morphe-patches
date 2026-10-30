package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val CONTEXTS = setOf(
    "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/view/ContextThemeWrapper;",
    "Landroid/app/Activity;", "Landroid/app/Application;", "Landroid/app/Service;",
)

private fun Any.isGetSystemService(): Boolean {
    if (this !is ReferenceInstruction || (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE)) return false
    val r = reference as? MethodReference ?: return false
    return r.definingClass in CONTEXTS && r.name == "getSystemService" &&
        r.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;") && r.returnType == "Ljava/lang/Object;"
}

/**
 * Sends every Context.getSystemService(String) in the app through [services]'s static
 * get(Context, String): off One UI Samsung's own services ("semeventdelegator" and the like) are null, and code that
 * casts them without a check dies; the extension answers those with a stand-in. Returns the calls moved.
 */
internal fun BytecodePatchContext.redirectSystemServices(services: String): Int {
    val target = "$services->get(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Object;"
    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any { it.isGetSystemService() } == true) sites += c.type to m
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
            if (!insn.isGetSystemService()) continue
            val call = when (insn) {
                is FiveRegisterInstruction -> "invoke-static { v${insn.registerC}, v${insn.registerD} }, $target"
                is RegisterRangeInstruction ->
                    "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + 1} }, $target"
                else -> continue
            }
            mutable.replaceInstruction(index, call)
            moved++
        }
    }
    return moved
}
