package org.ungoogled.patches.gmail

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The string a const-string (or /jumbo) loads, or null. */
internal fun Instruction.stringOrNull(): String? =
    if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
        ((this as ReferenceInstruction).reference as StringReference).string
    } else {
        null
    }

/** One method and the class it is in. */
internal class Found(val classDef: ClassDef, val method: Method) {
    val type: String get() = classDef.type
}

/**
 * Every method of Gmail's own code (not the extension's) that loads one of [wanted]: string to the methods
 * using it. Uses the patcher's index of string literals, so it does not walk the whole app.
 */
internal fun BytecodePatchContext.methodsUsing(wanted: Set<String>): Map<String, List<Found>> {
    val found = HashMap<String, MutableList<Found>>()
    for (string in wanted) {
        for (classDef in classDefByStrings(string, StringComparisonType.EQUALS)) {
            if (classDef.type.startsWith("Lorg/ungoogled/")) continue
            for (method in classDef.methods) {
                if (method.implementation?.instructions?.any { it.stringOrNull() == string } == true) {
                    found.getOrPut(string) { mutableListOf() } += Found(classDef, method)
                }
            }
        }
    }
    return found
}

/** The classes of Gmail's own code whose static initializer loads [string] (a class's own logger name). */
internal fun BytecodePatchContext.classesLogging(string: String): List<ClassDef> =
    classDefByStrings(string, StringComparisonType.EQUALS).filter { c ->
        !c.type.startsWith("Lorg/ungoogled/") &&
            c.methods.firstOrNull { it.name == "<clinit>" }?.implementation?.instructions?.any { it.stringOrNull() == string } == true
    }

/** The single method that loads [string], or a PatchException naming [what]. */
internal fun Map<String, List<Found>>.single(string: String, what: String): Found {
    val all = this[string].orEmpty()
    return all.singleOrNull() ?: throw PatchException("$what: expected one method using \"$string\", found ${all.size}")
}

/** The mutable copy of [found]'s method. */
internal fun BytecodePatchContext.mutable(found: Found) =
    mutableClassDefBy(found.type).methods.single {
        it.name == found.method.name && it.parameterTypes == found.method.parameterTypes && it.returnType == found.method.returnType
    }
