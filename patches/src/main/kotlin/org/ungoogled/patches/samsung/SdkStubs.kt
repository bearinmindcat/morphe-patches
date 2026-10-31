package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private fun Method.stubIndex(): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    for ((i, insn) in instructions.withIndex()) {
        if (insn.opcode != Opcode.NEW_INSTANCE) continue
        if (((insn as ReferenceInstruction).reference as TypeReference).type != "Ljava/lang/RuntimeException;") continue
        val next = instructions.getOrNull(i + 1) ?: continue
        if ((next.opcode == Opcode.CONST_STRING || next.opcode == Opcode.CONST_STRING_JUMBO) &&
            ((next as ReferenceInstruction).reference as StringReference).string == "Stub!"
        ) return i
    }
    return null
}

private fun defaultAnswer(type: String) = when (type) {
    "V" -> "return-void"
    "J", "D" -> "const-wide/16 v0, 0x0\nreturn-wide v0"
    "Z", "B", "S", "C", "I", "F" -> "const/4 v0, 0x0\nreturn v0"
    else -> "const/4 v0, 0x0\nreturn-object v0"
}

private fun outer(type: String) = if ('$' in type.substringAfterLast('/')) type.substringBefore('$') + ";" else type

/**
 * Some Samsung apps carry the SDK stubs of One UI framework classes (every body throws RuntimeException("Stub!")); on
 * One UI the framework's real classes load first, elsewhere the stubs run and throw. A stub method that the app's
 * extension implements ([implementations]: stub class (outer) -> extension class, with a static method of the same
 * name taking the receiver first and Object for the stub family's own types) now calls it; every other one answers
 * nothing (a constructor just returns). Returns (implemented, answered-nothing).
 */
internal fun BytecodePatchContext.replaceSdkStubs(implementations: Map<String, String>): Pair<Int, Int> {
    val stubs = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) if (m.stubIndex() != null) stubs += c.type to m
    }
    var implemented = 0
    var nothing = 0
    for ((type, method) in stubs) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val family = outer(type).removeSuffix(";")
        val erase = { t: String -> if (t.removePrefix("[").startsWith(family)) "Ljava/lang/Object;" else t }
        val static = AccessFlags.STATIC.isSet(method.accessFlags)
        val parameters = (if (static) emptyList() else listOf("Ljava/lang/Object;")) + method.parameterTypes.map { erase(it.toString()) }
        val target = implementations[outer(type)]?.let { impl ->
            mutableClassDefBy(impl).methods.firstOrNull {
                it.name == method.name && AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.parameterTypes.map { p -> p.toString() } == parameters && it.returnType == erase(method.returnType)
            }?.let { "$impl->${it.name}(${parameters.joinToString("")})${it.returnType}" }
        }
        if (target != null && method.name != "<init>") {
            val count = parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
            val first = mutable.implementation!!.registerCount - count
            val call = if (count == 0) "invoke-static { }, $target"
            else "invoke-static/range { v$first .. v${first + count - 1} }, $target"
            val result = when {
                method.returnType == "V" -> "return-void"
                method.returnType == "J" || method.returnType == "D" -> "move-result-wide v0\nreturn-wide v0"
                method.returnType.startsWith("L") || method.returnType.startsWith("[") ->
                    "move-result-object v0\n" + (if (erase(method.returnType) != method.returnType) "check-cast v0, ${method.returnType}\n" else "") + "return-object v0"
                else -> "move-result v0\nreturn v0"
            }
            mutable.addInstructions(0, "$call\n$result")
            implemented++
        } else {
            val index = mutable.stubIndex()!!
            val answer = if (method.name == "<init>") "return-void" else defaultAnswer(method.returnType)
            val lines = answer.lines()
            mutable.replaceInstruction(index, lines.first())
            if (lines.size > 1) mutable.addInstructions(index + 1, lines.drop(1).joinToString("\n"))
            nothing++
        }
    }
    return implemented to nothing
}
