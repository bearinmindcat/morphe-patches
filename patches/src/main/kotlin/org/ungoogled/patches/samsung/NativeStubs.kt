package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val LOAD_LIBRARY = "Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V"

private fun defaultReturn(type: String) = when (type) {
    "V" -> "return-void"
    "J" -> "const-wide/16 v0, 0x0\nreturn-wide v0"
    "D" -> "const-wide/16 v0, 0x0\nreturn-wide v0"
    "Z", "B", "S", "C", "I", "F" -> "const/4 v0, 0x0\nreturn v0"
    else -> "const/4 v0, 0x0\nreturn-object v0"
}

/**
 * Classes that bind to a native library only One UI can load (a Samsung system library, or one of the app's own that
 * links against one): their native methods become Java methods answering nothing (void, 0, false, null), so the
 * features built on them stay off instead of every first call throwing UnsatisfiedLinkError. A class is matched by its
 * System.loadLibrary("<name>") of one of [libraries], or by name in [boundClasses] (a class whose natives bind to such a
 * library that another class loads, e.g. a Kotlin companion). Returns the native methods replaced, by class.
 */
internal fun BytecodePatchContext.stubNativeLibraries(
    libraries: Set<String>,
    boundClasses: Set<String> = emptySet(),
): Map<String, Int> {
    val classes = mutableListOf<String>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        if (c.type in boundClasses && c.methods.any { AccessFlags.NATIVE.isSet(it.accessFlags) }) {
            classes += c.type
            return@classDefForEach
        }
        val loads = c.methods.any { m ->
            val instructions = m.implementation?.instructions?.toList() ?: return@any false
            instructions.withIndex().any { (i, insn) ->
                val ref = (insn as? ReferenceInstruction)?.reference
                if (insn.opcode != Opcode.INVOKE_STATIC || (ref as? MethodReference)?.let {
                        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
                    } != LOAD_LIBRARY
                ) return@any false
                val register = (insn as FiveRegisterInstruction).registerC
                instructions.subList(maxOf(0, i - 4), i).any { prior ->
                    (prior.opcode == Opcode.CONST_STRING || prior.opcode == Opcode.CONST_STRING_JUMBO) &&
                        (prior as OneRegisterInstruction).registerA == register &&
                        ((prior as ReferenceInstruction).reference as StringReference).string in libraries
                }
            }
        }
        if (loads && c.methods.any { AccessFlags.NATIVE.isSet(it.accessFlags) }) classes += c.type
    }
    val stubbed = sortedMapOf<String, Int>()
    for (type in classes) {
        val mutable = mutableClassDefBy(type)
        val natives = mutable.methods.filter { AccessFlags.NATIVE.isSet(it.accessFlags) }
        for (native in natives) {
            val static = AccessFlags.STATIC.isSet(native.accessFlags)
            val parameterRegisters = native.parameterTypes.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt() +
                if (static) 0 else 1
            val replacement = ImmutableMethod(
                type, native.name, native.parameters, native.returnType,
                native.accessFlags and AccessFlags.NATIVE.value.inv(), native.annotations, null,
                MutableMethodImplementation(2 + parameterRegisters),
            ).toMutable().apply { addInstructions(0, defaultReturn(native.returnType)) }
            mutable.methods.remove(native)
            mutable.methods.add(replacement)
        }
        stubbed[type] = natives.size
    }
    return stubbed
}
