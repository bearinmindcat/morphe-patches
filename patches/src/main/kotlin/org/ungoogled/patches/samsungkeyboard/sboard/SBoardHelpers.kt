package org.ungoogled.patches.samsungkeyboard.sboard

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.shared.addInstructionsAtLabel

internal const val SBOARD_EXTENSION_CLASS = "Lorg/ungoogled/samsungkeyboard/SBoard;"

internal val sboardLog: java.util.logging.Logger = java.util.logging.Logger.getLogger("SBoard")

internal fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
internal fun Instruction.call() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
}
internal fun Method.instructions() = implementation?.instructions?.toList().orEmpty()
internal fun Method.strings() = instructions().mapNotNull { it.string() }
internal fun Method.calls() = instructions().mapNotNull { it.call() }
internal fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
internal val Method.isStatic get() = AccessFlags.STATIC.isSet(accessFlags)

/** The registers an invoke passes, in order. */
internal fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

internal fun BytecodePatchContext.methodsWhere(predicate: (Method) -> Boolean): List<Method> {
    val found = mutableListOf<Method>()
    classDefForEach { c -> c.methods.filterTo(found) { it.implementation != null && predicate(it) } }
    return found
}

internal fun BytecodePatchContext.methodsWithString(string: String) = methodsWhere { m -> m.instructions().any { it.string() == string } }

internal fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

/** SemFloatingFeature / SemCscFeature getters the keyboard reads its feature switches with. */
internal const val FLOATING_GET_BOOLEAN = "Lcom/samsung/android/feature/SemFloatingFeature;->getBoolean(Ljava/lang/String;)Z"
internal const val FLOATING_GET_STRING = "Lcom/samsung/android/feature/SemFloatingFeature;->getString(Ljava/lang/String;)Ljava/lang/String;"
internal const val CSC_GET_STRING_1 = "Lcom/samsung/android/feature/SemCscFeature;->getString(Ljava/lang/String;)Ljava/lang/String;"
internal const val CSC_GET_STRING_2 =
    "Lcom/samsung/android/feature/SemCscFeature;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

/**
 * Every read of the switch [key] through [getter] (the key's string, then within 8 instructions the getter with it as
 * an argument, then its move-result) gets [answer] -- smali for the result register `{r}` -- right after the result is
 * kept. Returns how many reads were answered; fails when there are none.
 */
internal fun BytecodePatchContext.answerSwitch(key: String, getter: String, answer: String, vararg more: String): Int {
    val getters = setOf(getter, *more)
    var reads = 0
    for (reader in methodsWithString(key)) mutable(reader).apply {
        val instructions = implementation!!.instructions
        for (index in instructions.indices.filter { instructions[it].string() == key }.reversed()) {
            val keyRegister = (instructions[index] as OneRegisterInstruction).registerA
            val read = (index + 1 until minOf(index + 8, instructions.size)).firstOrNull {
                instructions[it].call() in getters && keyRegister in instructions[it].argumentRegisters()
            } ?: continue
            val result = instructions.getOrNull(read + 1)
                ?.takeIf { it.opcode == Opcode.MOVE_RESULT || it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: continue
            val smali = answer.replace("{r}", "v${(result as OneRegisterInstruction).registerA}")
            val before = countReal()
            addInstructionsAtLabel(read + 2, smali)
            checkInserted(before, smali)
            reads++
        }
    }
    if (reads == 0) throw PatchException("the keyboard's switch $key is not read through $getters")
    return reads
}

/** Logged-on keys the per-row visibility check of the settings knows (jadx cannot decompile it in any build). */
private val VISIBILITY_MARKERS = listOf("landscape_view_type", "split_keyboard_land")

/**
 * Hides settings rows by key: the settings' one visibility check (Context, key, Bundle) -> shown answers false for
 * [keys] before it looks at anything else.
 */
/** The settings' row visibility check and the register of its key parameter. */
private fun BytecodePatchContext.visibilityCheck(): Pair<MutableMethod, String> {
    val shape = setOf("Landroid/content/Context;", "Ljava/lang/String;", "Landroid/os/Bundle;")
    val check = methodsWhere { m ->
        m.returnType == "Z" && m.parameterTypes.size == 3 && m.parameterTypes.map(CharSequence::toString).toSet() == shape &&
            m.strings().containsAll(VISIBILITY_MARKERS)
    }.singleOrNull() ?: throw PatchException("the settings' row visibility check was not found")
    val index = check.parameterTypes.map(CharSequence::toString).indexOf("Ljava/lang/String;")
    return mutable(check) to "p${index + if (check.isStatic) 0 else 1}"
}

internal fun BytecodePatchContext.hideSettingsRows(vararg keys: String) {
    val (method, key) = visibilityCheck()
    val before = method.countReal()
    val smali =
        // The key is a high register in these big methods: copied to v1 first (invoke takes v0-v15 only).
        "move-object/from16 v1, $key\n" +
            keys.joinToString("\n") { "const-string v0, \"$it\"\ninvoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\nmove-result v0\nif-nez v0, :sboard_hide" } +
            "\ngoto :sboard_show\n:sboard_hide\nconst/4 v0, 0x0\nreturn v0\n:sboard_show\nnop"
    method.addInstructionsWithLabels(0, smali)
    method.checkInserted(before, smali)
}

/**
 * Morphe's smali step leaves out an instruction it can't encode (e.g. a 4-bit register above v15): fail instead. Nops
 * don't count -- the method's payload alignment adds or drops one.
 */
internal fun MutableMethod.checkInserted(before: Int, smali: String) {
    val expected = smali.lines().map(String::trim)
        .count { it.isNotEmpty() && !it.startsWith(":") && !it.startsWith("#") && it != "nop" }
    val added = implementation!!.instructions.count { it.opcode != Opcode.NOP } - before
    if (added != expected) throw PatchException("$definingClass->$name: $added of $expected instructions inserted")
}

/** Instructions other than nops, the count checkInserted compares against. */
internal fun MutableMethod.countReal() = implementation!!.instructions.count { it.opcode != Opcode.NOP }

/** Inserts [smali] at the very start of the method. */
internal fun MutableMethod.prepend(smali: String) = addInstructions(0, smali)

/** Logged as each toolbar button is registered (its one-argument registration method). */
private const val ADD_BUTTON_LOG = "addBee : "

/**
 * Removes toolbar buttons by id: the toolbar's registration of each button drops (and destroys) those, as it already
 * does for buttons that are "dead" on a phone. Their visibility can only grey a button out, not remove it.
 */
internal fun BytecodePatchContext.hideToolbarButtons(vararg ids: String) {
    val registration = methodsWithString(ADD_BUTTON_LOG).singleOrNull { it.returnType == "V" && it.parameterTypes.size == 1 }
        ?: throw PatchException("the toolbar's button registration ($ADD_BUTTON_LOG) was not found")
    val method = mutable(registration)
    val code = method.implementation!!.instructions
    fun reference(name: String, type: String) = code.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .firstOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == type }
        ?: throw PatchException("the toolbar's button registration has no $name()")
    val getId = reference("getBeeId", "Ljava/lang/String;")
    val destroy = reference("onDestroy", "V")
    if (method.implementation!!.registerCount - 2 < 2) throw PatchException("the toolbar's button registration has no spare registers")
    val kind = if (classDefByOrNull(getId.definingClass)?.accessFlags?.let { com.android.tools.smali.dexlib2.AccessFlags.INTERFACE.isSet(it) } == true) "interface" else "virtual"
    val before = method.countReal()
    val smali = "invoke-$kind/range {p1 .. p1}, ${getId.definingClass}->getBeeId()Ljava/lang/String;\nmove-result-object v1\n" +
        ids.joinToString("\n") { "const-string v0, \"$it\"\ninvoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\nmove-result v0\nif-nez v0, :sboard_drop" } +
        "\ngoto :sboard_keep\n:sboard_drop\n" +
        "invoke-${if (classDefByOrNull(destroy.definingClass)?.accessFlags?.let { com.android.tools.smali.dexlib2.AccessFlags.INTERFACE.isSet(it) } == true) "interface" else "virtual"}/range {p1 .. p1}, ${destroy.definingClass}->onDestroy()V\n" +
        "return-void\n:sboard_keep\nnop"
    method.addInstructionsWithLabels(0, smali)
    method.checkInserted(before, smali)
}
