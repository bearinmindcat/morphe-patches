package org.ungoogled.patches.samsungkeyboard.sboard

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.hasResourceId
import app.morphe.patcher.resource.resourceId
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.ungoogled.patches.shared.addInstructionsAtLabel

/** The title of "Turn on Show input method button on navigation bar?", the guide voice input opens instead. */
private const val GUIDE_TITLE = "honey_voice_guide_dialog_title"

private const val LAZY = "Lkotlin/Lazy;"

/**
 * One UI's voice input (space bar touch and hold, the side key) asks to turn on the keyboard button on the navigation
 * bar and stops there when that button is off. The guide decides with one check, "is the navigation bar's keyboard
 * button on?", read from the system settings the guide holds (a lazy field of the guide's class): every such read
 * now answers yes, so voice input starts as it would with the button on. Off One UI the button setting doesn't exist
 * and reads as on already. The button itself (and the bar it puts under the keyboard) is left as the user set it.
 */
@Suppress("unused")
val voiceInputWithoutNavButtonPatch = bytecodePatch(
    name = "Voice input without navigation bar button",
    description = "Starts voice input without asking for the keyboard button on the navigation bar.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    category(SBOARD)

    execute {
        if (!hasResourceId(ResourceType.STRING, GUIDE_TITLE)) throw PatchException("the keyboard has no string $GUIDE_TITLE")
        val title = resourceId(ResourceType.STRING, GUIDE_TITLE).toInt()

        // Some builds read resource ids from a (renamed) R class instead of a constant.
        val titleFields = mutableSetOf<String>()
        classDefForEach { c ->
            c.staticFields.forEach { f ->
                if (f.type == "I" && (f.initialValue as? IntEncodedValue)?.value == title) titleFields += "${c.type}->${f.name}:I"
            }
        }
        val constants = setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16)
        fun Instruction.isTitle() = (opcode in constants && (this as NarrowLiteralInstruction).narrowLiteral == title) ||
            (opcode == Opcode.SGET && ((this as ReferenceInstruction).reference as FieldReference).toString() in titleFields)

        // The guide: the dialog method of the one class whose ()V shows that title.
        val dialogs = methodsWhere { m -> m.returnType == "V" && m.parameterTypes.isEmpty() && m.instructions().any { it.isTitle() } }
        val guide = dialogs.map { it.definingClass }.distinct().singleOrNull()
            ?: throw PatchException("expected one class showing the voice input guide, found ${dialogs.map { it.definingClass }.distinct()}")
        val dialogKeys = dialogs.map { it.key() }.toSet()
        val lazies = classDefBy(guide).fields.filter { it.type == LAZY }.map { "$guide->${it.name}:$LAZY" }.toSet()
        if (lazies.isEmpty()) throw PatchException("the voice input guide $guide holds no lazy fields")

        // Each read of the guide's system settings: iget-object (guide's Lazy), Lazy.getValue, move-result-object,
        // check-casts, a ()Z call on it, move-result -> that result is now true.
        var checks = 0
        val readers = methodsWhere { m ->
            m.key() !in dialogKeys && m.instructions().any {
                it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).toString() in lazies
            }
        }
        for (reader in readers) mutable(reader).apply {
            val code = implementation!!.instructions
            val results = mutableListOf<Int>()
            for (index in code.indices) {
                val read = code[index]
                if (read.opcode != Opcode.IGET_OBJECT || ((read as ReferenceInstruction).reference as FieldReference).toString() !in lazies) continue
                var at = index + 1
                if (code.getOrNull(at)?.call() != "$LAZY->getValue()Ljava/lang/Object;") continue
                val value = (code.getOrNull(++at)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction)?.registerA ?: continue
                at++
                while (code.getOrNull(at)?.opcode == Opcode.CHECK_CAST) at++
                val check = code.getOrNull(at) ?: continue
                val r = (check as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (check.opcode !in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE) || r.parameterTypes.isNotEmpty() ||
                    r.returnType != "Z" || check.argumentRegisters() != listOf(value)
                ) continue
                val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction ?: continue
                results += at + 2
                results += result.registerA
            }
            results.chunked(2).reversed().forEach { (index, register) ->
                val smali = "const/16 v$register, 0x1"
                val before = countReal()
                addInstructionsAtLabel(index, smali)
                checkInserted(before, smali)
                checks++
            }
        }
        // Measured: 2 (space bar, side key) in the ...300 builds, 1 (the guide's own check) in the ...400 builds.
        if (checks == 0) throw PatchException("the voice input guide's navigation bar button check was not found")
        sboardLog.info("Voice input without navigation bar button: guide $guide, $checks checks")
    }
}
