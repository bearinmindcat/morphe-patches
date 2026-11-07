package org.ungoogled.patches.lggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.rewriter.TypeRewriter

private fun String.element() = trimStart('[')

private fun MethodReference.mentions(types: Set<String>) =
    definingClass.element() in types || returnType.element() in types || parameterTypes.any { it.toString().element() in types }

/** True if [c] names one of [types] anywhere: its superclass, a field, a method signature, an instruction or a catch. */
private fun ClassDef.mentions(types: Set<String>): Boolean {
    if (superclass in types || interfaces.any { it in types }) return true
    if (fields.any { it.type.element() in types }) return true
    for (m in methods) {
        if (m.returnType.element() in types || m.parameterTypes.any { it.toString().element() in types }) return true
        val implementation = m.implementation ?: continue
        if (implementation.tryBlocks.any { block -> block.exceptionHandlers.any { it.exceptionType in types } }) return true
        for (insn in implementation.instructions) {
            val reference = (insn as? ReferenceInstruction)?.reference ?: continue
            val hit = when (reference) {
                is TypeReference -> reference.type.element() in types
                is MethodReference -> reference.mentions(types)
                is FieldReference -> reference.definingClass.element() in types || reference.type.element() in types
                else -> false
            }
            if (hit) return true
        }
    }
    return false
}

/**
 * Replaces the classes [types] maps (old type to new type) by their replacements in every app class that names them
 * (not in our extensions, nor in classes [skip] accepts, e.g. the replaced library itself): superclass, fields, method
 * signatures, instructions and catch clauses. The replacement must have the members used, with the same signatures
 * once retyped. Returns the classes rewritten.
 */
internal fun BytecodePatchContext.retypeClasses(types: Map<String, String>, skip: (String) -> Boolean): List<String> {
    val rewriter = DexRewriter(object : RewriterModule() {
        override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> = object : TypeRewriter() {
            override fun rewriteUnwrappedType(value: String): String = types[value] ?: value
        }
    })
    val classes = mutableListOf<String>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/") || skip(c.type)) return@classDefForEach
        if (c.mentions(types.keys)) classes += c.type
    }
    for (type in classes) {
        val mutable = mutableClassDefBy(type)
        if (mutable.interfaces.any { it in types }) throw PatchException("$type implements a retyped interface")
        types[mutable.superclass]?.let { mutable.setSuperClass(it) }
        for (field in mutable.fields) {
            val retyped = rewriter.typeRewriter.rewrite(field.type)
            if (retyped != field.type) field.setType(retyped)
        }
        for (method in mutable.methods.toList()) {
            val rewritten = ImmutableMethod.of(rewriter.methodRewriter.rewrite(method)).toMutable()
            mutable.methods.remove(method)
            mutable.methods.add(rewritten)
        }
    }
    return classes
}

private val INVOKES = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE,
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
)

private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun defaultReturn(type: String) = when (type) {
    "V" -> "return-void"
    "J", "D" -> "const-wide/16 v0, 0x0\nreturn-wide v0"
    "Z", "B", "S", "C", "I", "F" -> "const/4 v0, 0x0\nreturn v0"
    else -> "const/4 v0, 0x0\nreturn-object v0"
}

private fun moveAndReturn(type: String) = when (type) {
    "V" -> "return-void"
    "J", "D" -> "move-result-wide v0\nreturn-wide v0"
    "Z", "B", "S", "C", "I", "F" -> "move-result v0\nreturn v0"
    else -> "move-result-object v0\nreturn-object v0"
}

/**
 * The native methods of [classes] (classes whose code is in a native library some phones cannot load) get a static
 * wrapper "<name>$ua" in their class that calls them only when [guard] ("Lowner;->name()Z") says the libraries are
 * there, else answers nothing (void, 0, false, null); every call to one of them in the app goes to its wrapper. Unlike
 * a stub, the real library still runs wherever it loads. Returns the native methods wrapped, by class.
 */
internal fun BytecodePatchContext.guardNativeMethods(classes: Set<String>, guard: String): Map<String, Int> {
    val wrappers = mutableMapOf<String, String>()
    val wrapped = sortedMapOf<String, Int>()
    for (type in classes) {
        val mutable = mutableClassDefBy(type)
        val natives = mutable.methods.filter { AccessFlags.NATIVE.isSet(it.accessFlags) }
        if (natives.isEmpty()) throw PatchException("$type has no native methods")
        for (native in natives) {
            val static = AccessFlags.STATIC.isSet(native.accessFlags)
            val parameters = (if (static) emptyList() else listOf(type)) + native.parameterTypes.map { it.toString() }
            val parameterRegisters = parameters.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
            val name = native.name + "\$ua"
            val invoke = when {
                static -> "invoke-static"
                AccessFlags.PRIVATE.isSet(native.accessFlags) -> "invoke-direct"
                else -> "invoke-virtual"
            }
            val target = "$type->${native.name}(${native.parameterTypes.joinToString("")})${native.returnType}"
            val call = if (parameterRegisters == 0) "$invoke {}, $target" else "$invoke/range { p0 .. p${parameterRegisters - 1} }, $target"
            val wrapper = ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, native.returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value, null, null,
                MutableMethodImplementation(2 + parameterRegisters),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-static {}, $guard
                        move-result v0
                        if-eqz v0, :absent
                        $call
                        ${moveAndReturn(native.returnType)}
                        :absent
                        ${defaultReturn(native.returnType)}
                    """,
                )
            }
            mutable.methods.add(wrapper)
            wrappers[target] = "$type->$name(${parameters.joinToString("")})${native.returnType}"
        }
        wrapped[type] = natives.size
    }

    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.name.endsWith("\$ua")) continue
            val found = m.implementation?.instructions?.any {
                it.opcode in INVOKES && ((it as ReferenceInstruction).reference as? MethodReference)?.key() in wrappers
            } == true
            if (found) sites += c.type to m
        }
    }
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            if (insn.opcode !in INVOKES) continue
            val to = ((insn as ReferenceInstruction).reference as? MethodReference)?.key()?.let { wrappers[it] } ?: continue
            val call = when (insn) {
                is FiveRegisterInstruction ->
                    listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                        .take(insn.registerCount).joinToString(", ", "invoke-static { ", " }, $to") { "v$it" }
                is RegisterRangeInstruction ->
                    "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + insn.registerCount - 1} }, $to"
                else -> throw PatchException("unexpected ${insn.opcode} in $type.${method.name}")
            }
            mutable.replaceInstruction(index, call)
        }
    }
    return wrapped
}
