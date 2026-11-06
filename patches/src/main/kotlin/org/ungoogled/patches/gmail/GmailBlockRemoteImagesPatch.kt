package org.ungoogled.patches.gmail

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL
import org.ungoogled.patches.shared.addInstructionsAtLabel

/** An account's settings, as Gmail's mail UI reads them; "show_images" is 0 for "Always display", 1 for "Ask before displaying". */
private const val SETTINGS = "Lcom/android/mail/providers/Settings;"
private const val SHOW_IMAGES = "show_images"
private const val ASK = 1

/**
 * Remote images in a message (tracking pixels among them) load when the message opens if the account's images setting
 * is "Always display external images" -- the default. Every account's settings now read "Ask before displaying": images
 * stay blocked until "Show pictures" is tapped, and senders you chose to always show images from still do.
 */
@Suppress("unused")
val gmailBlockRemoteImagesPatch = bytecodePatch(
    name = "Block remote images",
    description = "Loads images in mail only when you tap Show pictures, so tracking pixels don't fire when you open it.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GMAIL)

    execute {
        val settings = mutableClassDefBy(SETTINGS)
        // The images field: the int the cursor constructor stores right after reading the "show_images" column.
        val field = settings.methods.first { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/database/Cursor;") }
            .implementation!!.instructions.toList().let { code ->
                val at = code.indexOfFirst { it.stringOrNull() == SHOW_IMAGES }
                if (at < 0) throw PatchException("account settings no longer read \"$SHOW_IMAGES\"")
                code.drop(at).firstNotNullOfOrNull { insn ->
                    ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                        insn.opcode == Opcode.IPUT && it.definingClass == SETTINGS && it.type == "I"
                    }
                } ?: throw PatchException("account settings no longer store \"$SHOW_IMAGES\" in an int")
            }
        var constructors = 0
        for (method in settings.methods.filter { it.name == "<init>" }) {
            val code = method.implementation!!.instructions.toList()
            val writes = code.any { insn -> insn.opcode == Opcode.IPUT && ((insn as ReferenceInstruction).reference as FieldReference).name == field.name }
            if (!writes) continue
            // v0 is a local in every one of them (checked): all their locals are dead at return-void.
            val parameterRegisters = 1 + method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
            if (method.implementation!!.registerCount <= parameterRegisters) throw PatchException("settings constructor has no free register")
            // iput takes 4-bit registers: this (p0) has to be below v16.
            if (method.implementation!!.registerCount - parameterRegisters > 15) throw PatchException("settings constructor's this is past v15")
            val returns = code.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            for (index in returns.reversed()) {
                method.addInstructionsAtLabel(index, "const/4 v0, $ASK\niput v0, p0, $SETTINGS->${field.name}:I")
            }
            constructors++
        }
        // Measured: built from a cursor, a parcel, a builder, JSON and the defaults.
        if (constructors != 5) throw PatchException("expected 5 account settings constructors, found $constructors")
    }
}
