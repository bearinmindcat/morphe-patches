package org.ungoogled.patches.honorhome.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.samsung.packArguments

// Copied unchanged from samsunglauncher/misc/HiddenApis.kt (One UI Home), app-local there: to be shared by the launcher
// ports once they are merged.

private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun wide(type: String) = type == "J" || type == "D"

/** The registers an invoke passes, in order. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

/** Whether [insn] reads or writes register [r] (any register operand). */
private fun Instruction.touches(r: Int): Boolean = when (this) {
    is FiveRegisterInstruction, is RegisterRangeInstruction -> r in arguments()
    is OneRegisterInstruction -> registerA == r ||
        (this is com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction && registerB == r)
    else -> false
}

/**
 * Every `new X(...)` in the app (not in our extensions) whose constructor is one of [keys] ("LX;-><init>(params)V",
 * a constructor an installed app may not link to or the phone does not have) becomes a call to a generated factory in
 * [shimClass], which asks [helper]'s construct(String, Object[]) for the object: made by reflection where Android
 * allows it, or the port's own answer, else null. The `new-instance` is replaced by a null constant and the result
 * moved into the same register, so nothing else at the site changes. Returns the sites changed.
 */
internal fun BytecodePatchContext.redirectConstructors(helper: String, shimClass: String, keys: Set<String>): Int {
    val sem = mutableClassDefBy(shimClass)
    val shims = mutableMapOf<String, String>()

    fun shim(r: MethodReference): String = shims.getOrPut(r.key()) {
        val parameters = r.parameterTypes.map { it.toString() }
        val name = "c${shims.size}"
        val registers = parameters.sumOf { if (wide(it)) 2 else 1 }
        sem.methods.add(
            ImmutableMethod(
                shimClass, name, parameters.map { ImmutableMethodParameter(it, null, null) }, r.definingClass,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                MutableMethodImplementation(5 + registers),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        const-string v1, "${r.key()}"
                        ${if (parameters.isEmpty()) "const/4 v3, 0x0\nnew-array v2, v3, [Ljava/lang/Object;" else packArguments(parameters, 0)}
                        invoke-static { v1, v2 }, $helper->construct(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v0
                        check-cast v0, ${r.definingClass}
                        return-object v0
                    """,
                )
            },
        )
        "$shimClass->$name(${parameters.joinToString("")})${r.definingClass}"
    }

    fun Instruction.target(): MethodReference? {
        if (opcode != Opcode.INVOKE_DIRECT && opcode != Opcode.INVOKE_DIRECT_RANGE) return null
        val r = (this as ReferenceInstruction).reference as? MethodReference ?: return null
        return if (r.name == "<init>" && r.key() in keys) r else null
    }

    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) if (m.implementation?.instructions?.any { it.target() != null } == true) sites += c.type to m
    }

    var changed = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices.reversed()) {
            val insn = instructions[index]
            val r = insn.target() ?: continue
            val receiver = insn.arguments().first()
            // The new-instance that made the receiver; a constructor's own super() call has none and is left alone.
            var start = -1
            for (i in index - 1 downTo 0) {
                val before = instructions[i]
                if (before.opcode == Opcode.NEW_INSTANCE && (before as OneRegisterInstruction).registerA == receiver &&
                    ((before as ReferenceInstruction).reference as TypeReference).type == r.definingClass
                ) {
                    start = i
                    break
                }
                if (before.touches(receiver)) break
            }
            if (start < 0) continue
            if (receiver > 255) throw PatchException("register v$receiver too high in $type.${method.name}")
            val arguments = insn.arguments().drop(1)
            val call = when {
                arguments.isEmpty() -> "invoke-static { }, ${shim(r)}"
                insn is RegisterRangeInstruction ->
                    "invoke-static/range { v${arguments.first()} .. v${arguments.last()} }, ${shim(r)}"
                else -> arguments.joinToString(", ", "invoke-static { ", " }, ${shim(r)}") { "v$it" }
            }
            mutable.replaceInstruction(index, call)
            mutable.addInstruction(index + 1, "move-result-object v$receiver")
            mutable.replaceInstruction(start, if (receiver < 16) "const/4 v$receiver, 0x0" else "const/16 v$receiver, 0x0")
            changed++
        }
    }
    return changed
}

/**
 * App classes that extend an Android class whose constructor an installed app may not call (binder stubs of system
 * callbacks, listeners only system apps may create) or that the phone does not have: [bases] maps each such class to
 * the public class it is rebased onto and the interfaces the old base implemented. The class keeps its own code and
 * still implements the interface, so it loads and can be created; only system services it could never reach would
 * have used it. Returns the classes rebased.
 */
internal fun BytecodePatchContext.rebaseSubclasses(bases: Map<String, Pair<String, List<String>>>): Int {
    val targets = mutableListOf<String>()
    classDefForEach { c ->
        if (c.superclass in bases && !c.type.startsWith("Lorg/ungoogled/")) targets += c.type
    }
    for (type in targets) {
        val c = mutableClassDefBy(type)
        val old = c.superclass!!
        val (base, interfaces) = bases.getValue(old)
        c.setSuperClass(base)
        for (i in interfaces) if (i !in c.interfaces) c.interfaces.add(i)
        // A binder stub answers asBinder() with itself; the rebased class has to say it on its own.
        if (base == "Landroid/os/Binder;" && c.methods.none { it.name == "asBinder" && it.parameterTypes.isEmpty() }) {
            c.methods.add(
                ImmutableMethod(
                    type, "asBinder", emptyList(), "Landroid/os/IBinder;", AccessFlags.PUBLIC.value, null, null,
                    MutableMethodImplementation(1),
                ).toMutable().apply { addInstructions(0, "return-object p0") },
            )
        }
        for (m in c.methods) {
            val instructions = m.implementation?.instructions ?: continue
            for (index in instructions.indices) {
                val insn = instructions[index]
                if (insn !is ReferenceInstruction) continue
                val r = insn.reference as? MethodReference ?: continue
                if (r.definingClass != old) continue
                val superCall = insn.opcode == Opcode.INVOKE_SUPER || insn.opcode == Opcode.INVOKE_SUPER_RANGE
                val ownInit = (insn.opcode == Opcode.INVOKE_DIRECT || insn.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                    r.name == "<init>" && m.name == "<init>"
                if (!superCall && !ownInit) continue
                val args = insn.arguments()
                if (ownInit) {
                    // super(...) -> the new base's no-argument constructor.
                    m.replaceInstruction(index, "invoke-direct { v${args.first()} }, $base-><init>()V")
                } else {
                    val shape = "${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}"
                    val opcode = if (insn is RegisterRangeInstruction) "invoke-super/range" else "invoke-super"
                    val regs = if (insn is RegisterRangeInstruction) "{ v${args.first()} .. v${args.last()} }"
                        else args.joinToString(", ", "{ ", " }") { "v$it" }
                    m.replaceInstruction(index, "$opcode $regs, $base->$shape")
                }
            }
        }
    }
    // Fields and return values typed as an old base can no longer hold the rebased objects (the verifier rejects the
    // whole class): such a field is left empty and such a method answers null. Only the system could have used them.
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        val stores = c.methods.filter { m ->
            m.implementation?.instructions?.any { insn ->
                (insn.opcode == Opcode.IPUT_OBJECT || insn.opcode == Opcode.SPUT_OBJECT) &&
                    ((insn as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.FieldReference).type in bases
            } == true || (m.returnType in bases && m.implementation != null)
        }
        if (stores.isEmpty()) return@classDefForEach
        val mutable = mutableClassDefBy(c.type)
        for (method in stores) {
            val m = mutable.methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = m.implementation!!.instructions
            for (index in instructions.indices.reversed()) {
                val insn = instructions[index]
                when (insn.opcode) {
                    Opcode.IPUT_OBJECT, Opcode.SPUT_OBJECT -> {
                        val f = (insn as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.FieldReference
                        if (f.type in bases) m.replaceInstruction(index, "nop")
                    }
                    Opcode.RETURN_OBJECT -> if (m.returnType in bases) {
                        val r = (insn as OneRegisterInstruction).registerA
                        m.addInstruction(index, if (r < 16) "const/4 v$r, 0x0" else "const/16 v$r, 0x0")
                    }
                    else -> {}
                }
            }
        }
    }
    return targets.size
}

/**
 * The generated shims in [shimClass] end with `check-cast v0, T` + `return-object v0`. For a class the phone does not
 * have, ART resolves T even for a null answer and throws; a null answer now returns before the cast. Returns the
 * shims changed.
 */
internal fun BytecodePatchContext.skipCastOfNull(shimClass: String): Int {
    var changed = 0
    for (m in mutableClassDefBy(shimClass).methods) {
        val instructions = m.implementation?.instructions ?: continue
        val index = instructions.indexOfLast { it.opcode == Opcode.CHECK_CAST }
        if (index < 0 || (instructions[index] as OneRegisterInstruction).registerA != 0) continue
        if (instructions.getOrNull(index + 1)?.opcode != Opcode.RETURN_OBJECT) continue
        m.addInstructionsWithLabels(
            index,
            """
                if-nez v0, :cast
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("cast", instructions[index]),
        )
        changed++
    }
    return changed
}
