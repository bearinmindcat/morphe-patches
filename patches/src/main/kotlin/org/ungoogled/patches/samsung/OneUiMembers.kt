package org.ungoogled.patches.samsung

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** One UI's additions to Android's classes: sem... methods and fields, SEM_... constants. */
private val ONE_UI_MEMBER = Regex("^(sem[A-Z]|SEM_|mSem[A-Z])")

private val BOXES = mapOf(
    "Z" to ("Ljava/lang/Boolean;" to "booleanValue"),
    "B" to ("Ljava/lang/Byte;" to "byteValue"),
    "S" to ("Ljava/lang/Short;" to "shortValue"),
    "C" to ("Ljava/lang/Character;" to "charValue"),
    "I" to ("Ljava/lang/Integer;" to "intValue"),
    "J" to ("Ljava/lang/Long;" to "longValue"),
    "F" to ("Ljava/lang/Float;" to "floatValue"),
    "D" to ("Ljava/lang/Double;" to "doubleValue"),
)

private val SGET = setOf(Opcode.SGET, Opcode.SGET_WIDE, Opcode.SGET_OBJECT, Opcode.SGET_BOOLEAN, Opcode.SGET_BYTE, Opcode.SGET_CHAR, Opcode.SGET_SHORT)
private val IGET = setOf(Opcode.IGET, Opcode.IGET_WIDE, Opcode.IGET_OBJECT, Opcode.IGET_BOOLEAN, Opcode.IGET_BYTE, Opcode.IGET_CHAR, Opcode.IGET_SHORT)
private val IPUT = setOf(Opcode.IPUT, Opcode.IPUT_WIDE, Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_BYTE, Opcode.IPUT_CHAR, Opcode.IPUT_SHORT)
private val SPUT = setOf(Opcode.SPUT, Opcode.SPUT_WIDE, Opcode.SPUT_OBJECT, Opcode.SPUT_BOOLEAN, Opcode.SPUT_BYTE, Opcode.SPUT_CHAR, Opcode.SPUT_SHORT)

private fun wide(type: String) = type == "J" || type == "D"
private fun registers(types: List<String>) = types.sumOf { if (wide(it)) 2 else 1 }

private fun moveResult(type: String) = when {
    wide(type) -> "move-result-wide"
    type.startsWith("L") || type.startsWith("[") -> "move-result-object"
    else -> "move-result"
}

/** Turns the helper's Object answer in v0 into the shim's own return. */
internal fun returnFrom(type: String): String {
    if (type == "V") return "return-void"
    val box = BOXES[type] ?: return "check-cast v0, $type\nreturn-object v0"
    return """
        check-cast v0, ${box.first}
        invoke-virtual { v0 }, ${box.first}->${box.second}()$type
        ${moveResult(type)} v0
        ${if (wide(type)) "return-wide" else "return"} v0
    """
}

/** Puts parameters [from] .. of the shim into the Object[] in v2 (index v3, box in v4). */
internal fun packArguments(parameters: List<String>, from: Int): String = buildString {
    appendLine("const/16 v3, ${parameters.size - from}")
    appendLine("new-array v2, v3, [Ljava/lang/Object;")
    var p = registers(parameters.take(from))
    for ((index, type) in parameters.withIndex().drop(from)) {
        appendLine("const/16 v3, ${index - from}")
        val box = BOXES[type]
        if (box == null) {
            appendLine("aput-object p$p, v2, v3")
        } else {
            val last = if (wide(type)) p + 1 else p
            appendLine("invoke-static/range { p$p .. p$last }, ${box.first}->valueOf($type)${box.first}")
            appendLine("move-result-object v4")
            appendLine("aput-object v4, v2, v3")
        }
        p += if (wide(type)) 2 else 1
    }
}

/**
 * Sends an app's uses of One UI's additions to Android classes through its extension's OneUi class, which uses them
 * where the phone has them and answers "not there" elsewhere: off One UI each one is a NoSuchMethodError or
 * NoSuchFieldError the moment it runs. One generated method in [sem] per member, with the call's or field access's
 * own shape, so each site only changes its target. [oneUi] and [sem] are the app extension's two classes (each app
 * carries its own copy, since an extension is merged into one app). Returns the calls and field accesses moved.
 */
internal fun BytecodePatchContext.redirectOneUiMembers(oneUi: String, sem: String): Pair<Int, Int> =
    redirectMembers(oneUi, sem) { owner, name -> owner.startsWith("Landroid/") && ONE_UI_MEMBER.containsMatchIn(name) }

/**
 * The machinery behind redirectOneUiMembers, for any vendor: every call to and field access of a member for which
 * [isMember] (owner class, member name) says yes goes through [helper]'s call/callSuper/get/set, via one generated
 * method per member in [shimClass]. Also used for HyperOS (xiaomi/MiuiMembers). With [keys] ("Lowner;->name(params)
 * return" / "Lowner;->name:type") only exactly those members move, and [isMember] is not asked (RedMagic: hidden
 * Android members, whose public overloads must stay as they are).
 */
internal fun BytecodePatchContext.redirectMembers(
    helper: String,
    shimClass: String,
    keys: Set<String>? = null,
    isMember: (owner: String, name: String) -> Boolean,
): Pair<Int, Int> {
    val oneUi = helper
    val sem = shimClass
    val semClass = mutableClassDefBy(sem)
    val shims = mutableMapOf<String, String>()

    fun shim(key: String, parameters: List<String>, returnType: String, body: String): String = shims.getOrPut(key) {
        val name = "m${shims.size}"
        semClass.methods.add(
            ImmutableMethod(
                sem, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                MutableMethodImplementation(5 + registers(parameters)),
            ).toMutable().apply { addInstructions(0, body) },
        )
        "$sem->$name(${parameters.joinToString("")})$returnType"
    }

    fun methodShim(r: MethodReference, receiver: Boolean, superCall: Boolean): String {
        val key = "${r.definingClass}->${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}"
        val parameters = (if (receiver) listOf(r.definingClass) else emptyList()) + r.parameterTypes.map { it.toString() }
        val helper = if (superCall) "callSuper" else "call"
        return shim(
            (if (superCall) "super " else "") + key, parameters, r.returnType,
            """
                const-string v1, "$key"
                ${packArguments(parameters, if (receiver) 1 else 0)}
                ${if (receiver) "move-object/from16 v0, p0" else "const/4 v0, 0x0"}
                invoke-static { v0, v1, v2 }, $oneUi->$helper(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                ${returnFrom(r.returnType)}
            """,
        )
    }

    fun getterShim(f: FieldReference, receiver: Boolean): String {
        val key = "${f.definingClass}->${f.name}:${f.type}"
        return shim(
            "get $key", if (receiver) listOf(f.definingClass) else emptyList(), f.type,
            """
                const-string v1, "$key"
                ${if (receiver) "move-object/from16 v0, p0" else "const/4 v0, 0x0"}
                invoke-static { v0, v1 }, $oneUi->get(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;
                move-result-object v0
                ${returnFrom(f.type)}
            """,
        )
    }

    fun setterShim(f: FieldReference, receiver: Boolean): String {
        val key = "${f.definingClass}->${f.name}:${f.type}"
        val parameters = (if (receiver) listOf(f.definingClass) else emptyList()) + f.type
        return shim(
            "set $key", parameters, "V",
            """
                const-string v1, "$key"
                ${packArguments(parameters, if (receiver) 1 else 0)}
                const/4 v3, 0x0
                aget-object v4, v2, v3
                ${if (receiver) "move-object/from16 v0, p0" else "const/4 v0, 0x0"}
                invoke-static { v0, v1, v4 }, $oneUi->set(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)V
                return-void
            """,
        )
    }

    fun oneUi(owner: String, name: String) = isMember(owner, name)
    fun oneUi(ref: MethodReference) = if (keys == null) oneUi(ref.definingClass, ref.name)
        else "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}" in keys
    fun oneUi(ref: FieldReference) = if (keys == null) oneUi(ref.definingClass, ref.name)
        else "${ref.definingClass}->${ref.name}:${ref.type}" in keys

    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            val hit = m.implementation?.instructions?.any { insn ->
                when (val ref = (insn as? ReferenceInstruction)?.reference) {
                    is MethodReference -> oneUi(ref)
                    is FieldReference -> oneUi(ref)
                    else -> false
                }
            } ?: false
            if (hit) sites += c.type to m
        }
    }

    var calls = 0
    var fields = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        // Back to front: a field read becomes two instructions, which moves everything after it.
        for (index in instructions.indices.reversed()) {
            val insn = instructions[index]
            when (val ref = (insn as? ReferenceInstruction)?.reference) {
                is MethodReference -> {
                    if (!oneUi(ref)) continue
                    val (receiver, superCall) = when (insn.opcode) {
                        Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> false to false
                        Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE -> true to true
                        else -> true to false
                    }
                    val target = methodShim(ref, receiver, superCall)
                    val call = when (insn) {
                        is FiveRegisterInstruction ->
                            listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                                .take(insn.registerCount).joinToString(", ", "invoke-static { ", " }, $target") { "v$it" }
                        is RegisterRangeInstruction ->
                            "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + insn.registerCount - 1} }, $target"
                        else -> throw PatchException("unexpected ${insn.opcode} in $type.${method.name}")
                    }
                    mutable.replaceInstruction(index, call)
                    calls++
                }
                is FieldReference -> {
                    if (!oneUi(ref)) continue
                    when (insn.opcode) {
                        in SGET -> {
                            val a = (insn as OneRegisterInstruction).registerA
                            mutable.replaceInstruction(index, "invoke-static { }, ${getterShim(ref, false)}")
                            mutable.addInstruction(index + 1, "${moveResult(ref.type)} v$a")
                        }
                        in IGET -> {
                            val i = insn as TwoRegisterInstruction
                            mutable.replaceInstruction(index, "invoke-static/range { v${i.registerB} .. v${i.registerB} }, ${getterShim(ref, true)}")
                            mutable.addInstruction(index + 1, "${moveResult(ref.type)} v${i.registerA}")
                        }
                        in IPUT -> {
                            val i = insn as TwoRegisterInstruction
                            val value = if (wide(ref.type)) "v${i.registerA}, v${i.registerA + 1}" else "v${i.registerA}"
                            if (i.registerB > 15 || i.registerA + 1 > 15) throw PatchException("registers too high in $type.${method.name}")
                            mutable.replaceInstruction(index, "invoke-static { v${i.registerB}, $value }, ${setterShim(ref, true)}")
                        }
                        in SPUT -> {
                            val i = insn as OneRegisterInstruction
                            val value = if (wide(ref.type)) "v${i.registerA}, v${i.registerA + 1}" else "v${i.registerA}"
                            if (i.registerA + 1 > 15) throw PatchException("registers too high in $type.${method.name}")
                            mutable.replaceInstruction(index, "invoke-static { $value }, ${setterShim(ref, false)}")
                        }
                        else -> throw PatchException("unexpected ${insn.opcode} on ${ref.name} in $type.${method.name}")
                    }
                    fields++
                }
                else -> {}
            }
        }
    }
    return calls to fields
}
