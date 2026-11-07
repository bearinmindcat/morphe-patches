package org.ungoogled.patches.samsunginternet

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Samsung Internet's own code keeps its class and method names (only libraries are renamed), so its methods are found
 * by class, name and signature, and every one is checked for the shape the patch expects before it is changed.
 */

/** [type]'s method [name] taking [parameters] (dex types), or a PatchException naming it. */
internal fun BytecodePatchContext.method(type: String, name: String, vararg parameters: String): MutableMethod {
    val classDef = mutableClassDefByOrNull(type) ?: throw PatchException("${type.simpleName()} not found")
    return classDef.methods.firstOrNull { m ->
        m.name == name && m.parameterTypes.map(CharSequence::toString) == parameters.toList()
    } ?: throw PatchException("${type.simpleName()}.$name(${parameters.joinToString("")}) not found")
}

internal fun String.simpleName() = removeSuffix(";").substringAfterLast('/')

/** Makes the method return at once: nothing, false / 0, or null. */
internal fun MutableMethod.returnEarly(value: Int = 0) = addInstructions(
    0,
    when (returnType.first()) {
        'V' -> "return-void"
        'L', '[' -> "const/4 v0, 0x0\nreturn-object v0"
        else -> "const/4 v0, 0x$value\nreturn v0"
    },
)

/**
 * Every call in this method to a method [callee] accepts has its boolean answer replaced by false: the move-result
 * after it becomes `const/4 vX, 0x0` (the call still runs; a replace keeps the location's labels). Returns how many.
 */
internal fun MutableMethod.answerFalse(callee: (MethodReference) -> Boolean): Int {
    val instructions = implementation!!.instructions
    val results = instructions.withIndex().filter { (index, insn) ->
        val reference = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@filter false
        callee(reference) && reference.returnType == "Z" &&
            instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT
    }.map { it.index + 1 }
    for (index in results) {
        val register = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
        if (register > 15) throw PatchException("$name: result register v$register out of const/4 range")
        replaceInstruction(index, "const/4 v$register, 0x0")
    }
    return results.size
}

/** A static method named [name] on [type] (any parameters). */
internal fun call(type: String, name: String): (MethodReference) -> Boolean = { it.definingClass == type && it.name == name }
