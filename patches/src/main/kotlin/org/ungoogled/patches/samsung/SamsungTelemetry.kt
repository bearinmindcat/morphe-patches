package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Samsung's diagnostics agent: DiagMon's crash, ANR and performance reports and Samsung Analytics' logs go through it. */
private const val DIAGNOSTICS_AGENT = "com.sec.android.diagmonagent"

/** The broadcast Samsung's SDKs send their feature-usage surveys with, to One UI's context service. */
private const val FEATURE_SURVEY = "com.samsung.android.providers.context.log.action.USE_APP_FEATURE_SURVEY"

private val CONST_STRING = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)

private fun Instruction.constString() =
    if (opcode in CONST_STRING) ((this as ReferenceInstruction).reference as StringReference).string else null

private fun Instruction.methodCall() = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.passes(register: Int) = when (this) {
    is FiveRegisterInstruction -> register in listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> register in startRegister until startRegister + registerCount
    else -> false
}

/** Indices of the const-string [value] instructions whose register the next call passes to a method [callee] accepts. */
private fun List<Instruction>.arguments(value: String, callee: (MethodReference) -> Boolean) =
    withIndex().filter { (index, insn) ->
        if (insn.constString() != value) return@filter false
        val call = drop(index + 1).firstOrNull { it.methodCall() != null } ?: return@filter false
        callee(call.methodCall()!!) && call.passes((insn as OneRegisterInstruction).registerA)
    }.map { it.index }

/**
 * Replaces every const-string [value] that the next call passes to a method [callee] accepts with "", a name nothing
 * has. Returns how many were replaced.
 */
internal fun MutableMethod.blankArguments(value: String, callee: (MethodReference) -> Boolean): Int {
    val indices = implementation!!.instructions.toList().arguments(value, callee)
    for (index in indices) {
        val register = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
        replaceInstruction(index, "const-string v$register, \"\"")
    }
    return indices.size
}

/** A package-manager lookup by name: getPackageInfo, resolveContentProvider, ... */
internal fun packageManagerCall(name: String): (MethodReference) -> Boolean =
    { it.definingClass == "Landroid/content/pm/PackageManager;" && it.name == name }

/** Every method that [predicate] accepts, by its defining class, name and signature (read-only pass). */
private fun BytecodePatchContext.methodsWhere(predicate: (Method) -> Boolean): List<Method> {
    val found = mutableListOf<Method>()
    classDefForEach { c -> c.methods.filterTo(found) { it.implementation != null && predicate(it) } }
    return found
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

/**
 * Every lookup of Samsung's diagnostics agent asks for a package nothing has. The DiagMon SDK then takes its own "agent
 * not installed" path (NO_DMA): no configuration, so no crash or ANR handler and no report; it is what the SDK does on
 * a phone without the agent. Returns how many lookups were changed.
 */
internal fun BytecodePatchContext.hideDiagnosticsAgent(): Int {
    val lookup = packageManagerCall("getPackageInfo")
    val count = methodsWhere { it.implementation!!.instructions.toList().arguments(DIAGNOSTICS_AGENT, lookup).isNotEmpty() }
        .sumOf { mutable(it).blankArguments(DIAGNOSTICS_AGENT, lookup) }
    if (count == 0) throw PatchException("no lookup of Samsung's diagnostics agent ($DIAGNOSTICS_AGENT) found")
    return count
}

/**
 * Samsung's SDKs (handwriting, S Pen, multi-window) report which of their features are used to One UI's context
 * service, each from a small sender of its own. Every sender returns at once. Returns how many there were.
 */
internal fun BytecodePatchContext.muteFeatureSurveys(): Int {
    val senders = methodsWhere { m ->
        m.returnType == "V" && !m.name.startsWith("<") && m.implementation!!.instructions.any { it.constString() == FEATURE_SURVEY }
    }
    if (senders.isEmpty()) throw PatchException("no feature survey sender ($FEATURE_SURVEY) found")
    senders.forEach { mutable(it).addInstructions(0, "return-void") }
    return senders.size
}
