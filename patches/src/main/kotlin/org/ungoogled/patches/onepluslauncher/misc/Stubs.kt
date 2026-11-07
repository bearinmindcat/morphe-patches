package org.ungoogled.patches.onepluslauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val RUNTIME_EXCEPTION = "Ljava/lang/RuntimeException;"

private const val CONTEXT = "Landroid/content/Context;"
private const val ATTRS = "Landroid/util/AttributeSet;"
private val VIEW_CONSTRUCTORS = listOf(
    listOf(CONTEXT), listOf(CONTEXT, ATTRS), listOf(CONTEXT, ATTRS, "I"), listOf(CONTEXT, ATTRS, "I", "I"),
)

/** Public constructors of the Android classes the stubs extend (anything else: the no-argument one). */
private val FRAMEWORK_CONSTRUCTORS = mapOf(
    "Ljava/lang/Enum;" to listOf(listOf("Ljava/lang/String;", "I")),
    "Landroid/view/View;" to VIEW_CONSTRUCTORS,
    "Landroid/view/ViewGroup;" to VIEW_CONSTRUCTORS,
    "Landroid/widget/LinearLayout;" to VIEW_CONSTRUCTORS,
    "Landroid/widget/FrameLayout;" to VIEW_CONSTRUCTORS,
    "Landroid/widget/ScrollView;" to VIEW_CONSTRUCTORS,
    "Landroid/widget/TextView;" to VIEW_CONSTRUCTORS,
    "Landroid/widget/OverScroller;" to listOf(listOf(CONTEXT), listOf(CONTEXT, "Landroid/view/animation/Interpolator;")),
    "Landroid/app/ProgressDialog;" to listOf(listOf(CONTEXT), listOf(CONTEXT, "I")),
    "Landroid/os/Handler;" to listOf(listOf("Landroid/os/Looper;"), emptyList()),
)

private fun wide(type: String) = type == "J" || type == "D"
private fun registers(types: List<String>) = types.sumOf { if (wide(it)) 2 else 1 }

/** Where a compile-time stub body (`throw new RuntimeException("stub")`) starts in [instructions], or -1. */
private fun stubStart(instructions: List<Instruction>): Int {
    for (i in 0 until instructions.size - 3) {
        val a = instructions[i]
        if (a.opcode != Opcode.NEW_INSTANCE || ((a as ReferenceInstruction).reference as TypeReference).type != RUNTIME_EXCEPTION) continue
        val b = instructions[i + 1]
        if (b.opcode != Opcode.CONST_STRING || ((b as ReferenceInstruction).reference as StringReference).string != "stub") continue
        val c = instructions[i + 2]
        val r = (c as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (c.opcode != Opcode.INVOKE_DIRECT || r.definingClass != RUNTIME_EXCEPTION || r.name != "<init>") continue
        if (instructions[i + 3].opcode != Opcode.THROW) continue
        return i
    }
    return -1
}

/**
 * A `synchronized` stub takes its lock before the throw: `monitor-enter p0`, or for a static one `const-class` of its
 * class and `monitor-enter` on that (getInstance() of OplusResourceManager and 32 more).
 */
private fun lockOnly(prologue: List<Instruction>) = prologue.isNotEmpty() &&
    prologue.last().opcode == Opcode.MONITOR_ENTER &&
    prologue.all { it.opcode == Opcode.CONST_CLASS || it.opcode == Opcode.MONITOR_ENTER }

/** Smali that zeroes the registers from v1 on for [types], and those registers as a list (after "v0"). */
private fun zeroArguments(types: List<String>): Pair<String, String> {
    val code = StringBuilder()
    val regs = mutableListOf<String>()
    var r = 1
    for (t in types) {
        if (wide(t)) {
            code.appendLine("const-wide/16 v$r, 0x0")
            regs += "v$r"; regs += "v${r + 1}"; r += 2
        } else {
            code.appendLine("const/4 v$r, 0x0")
            regs += "v$r"; r += 1
        }
    }
    return code.toString() to regs.joinToString(", ")
}

/**
 * Apps built against a phone maker's SDK sometimes carry its compile-time stubs: classes of the maker's framework whose
 * every method is `throw new RuntimeException("stub")`. On the maker's phones the framework's own classes load first,
 * so the stubs never run; elsewhere they are the only copy and every call throws. Each such body becomes an "absent"
 * answer: a constructor only calls its superclass's (see rebuildConstructor), a method returns nothing / 0 / false / null, and a static method returning its
 * own class (getInstance and the like) returns an object of it made with one of its (now empty) constructors. Returns
 * the methods changed.
 */
/**
 * A stub constructor becomes a call of its superclass's constructor: the one with the same parameters where there is
 * one (a view's (Context, AttributeSet) and the like), else the one with the fewest, given zeros / nulls.
 */
private fun BytecodePatchContext.rebuildConstructor(
    mutableClass: app.morphe.patcher.util.proxy.mutableTypes.MutableClass,
    ctor: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod,
) {
    val type = mutableClass.type
    val parameters = ctor.parameterTypes.map { it.toString() }
    val superType = mutableClass.superclass ?: "Ljava/lang/Object;"
    val superConstructors: List<List<String>> = when {
        superType == "Ljava/lang/Object;" -> listOf(emptyList())
        classDefByOrNull(superType) != null -> classDefBy(superType).methods.filter { it.name == "<init>" }
            .map { m -> m.parameterTypes.map { it.toString() } }
        else -> FRAMEWORK_CONSTRUCTORS[superType] ?: listOf(emptyList())
    }.ifEmpty { listOf(emptyList()) }
    val paramRegisters = registers(parameters) + 1
    val (locals, body) = if (parameters in superConstructors) {
        0 to """
            invoke-direct/range { p0 .. p${paramRegisters - 1} }, $superType-><init>(${parameters.joinToString("")})V
            return-void
        """
    } else {
        val args = superConstructors.minBy { it.size }
        (1 + registers(args)) to """
            move-object/from16 v0, p0
            ${zeroArguments(args).first}
            invoke-direct/range { v0 .. v${registers(args)} }, $superType-><init>(${args.joinToString("")})V
            return-void
        """
    }
    mutableClass.methods.remove(ctor)
    mutableClass.methods.add(
        ImmutableMethod(
            type, "<init>", parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            ctor.accessFlags, null, null, MutableMethodImplementation(locals + paramRegisters),
        ).toMutable().apply { addInstructions(0, body) },
    )
}

internal fun BytecodePatchContext.neutralizeStubs(): Int {
    val targets = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            val instructions = m.implementation?.instructions?.toList() ?: continue
            val start = stubStart(instructions)
            if (start < 0) continue
            // Only whole-body stubs: nothing before the throw but a constructor's super() call or a method's lock.
            if (m.name == "<init>") {
                if (instructions.take(start).any { it.opcode != Opcode.INVOKE_DIRECT && it.opcode != Opcode.INVOKE_DIRECT_RANGE }) continue
            } else if (start != 0 && !lockOnly(instructions.take(start))) continue
            targets += c.type to m
        }
    }
    val byClass = targets.groupBy { it.first }
    var changed = 0
    for ((type, methods) in byClass) {
        val mutableClass = mutableClassDefBy(type)
        // A constructor to make "self" objects with: the one with the fewest parameters.
        val constructor = mutableClass.methods.filter { it.name == "<init>" }.minByOrNull { it.parameterTypes.size }
        for ((_, method) in methods) {
            val mutable = mutableClass.methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            if (mutable.name == "<init>") {
                rebuildConstructor(mutableClass, mutable)
                changed++
                continue
            }
            val ret = mutable.returnType
            val isStatic = AccessFlags.STATIC.isSet(mutable.accessFlags)
            var needed = 2
            val body = when {
                ret == "V" -> "return-void"
                ret == "J" || ret == "D" -> "const-wide/16 v0, 0x0\nreturn-wide v0"
                ret.length == 1 -> "const/4 v0, 0x0\nreturn v0"
                ret == type && isStatic && constructor != null -> {
                    val args = constructor.parameterTypes.map { it.toString() }
                    needed = maxOf(needed, 1 + registers(args))
                    val zero = zeroArguments(args).first
                    """
                        new-instance v0, $type
                        $zero
                        invoke-direct/range { v0 .. v${registers(args)} }, $type-><init>(${args.joinToString("")})V
                        return-object v0
                    """
                }
                else -> "const/4 v0, 0x0\nreturn-object v0"
            }
            val parameters = mutable.parameterTypes.map { it.toString() }
            val paramRegisters = registers(parameters) + if (isStatic) 0 else 1
            mutableClass.methods.remove(mutable)
            mutableClass.methods.add(
                ImmutableMethod(
                    type, mutable.name, parameters.map { ImmutableMethodParameter(it, null, null) }, ret,
                    mutable.accessFlags, null, null, MutableMethodImplementation(needed + paramRegisters),
                ).toMutable().apply { addInstructions(0, body) },
            )
            changed++
        }
    }
    return changed
}
