package org.ungoogled.patches.samsungmusic.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.ungoogled.patches.samsung.textSizesToSp
import org.ungoogled.patches.samsungmusic.SamsungMusicConstants.COMPATIBILITY_SAMSUNG_MUSIC

/** Music's text view for list rows, titles and the player; unobfuscated (layouts name it). */
private const val ONE_UI_TEXT_VIEW = "Lcom/samsung/android/app/musiclibrary/ui/widget/OneUiTextView;"
private const val SET_TEXT_SIZE = "Landroid/widget/TextView;->setTextSize(IF)V"

/** Tabs, the album grid, artist lines and track numbers are in dp; they become sp (see textSizesToSp). */
private val textSizesPatch = resourcePatch {
    execute {
        val changed = textSizesToSp()
        // Measured against 16.2.45.1: 347 values.
        java.util.logging.Logger.getLogger("SamsungMusic").info("Text sizes in sp: $changed")
        if (changed < 100) throw PatchException("only $changed of Samsung Music's text sizes were in dp")
    }
}

@Suppress("unused")
val followFontSizePatch = bytecodePatch(
    name = "Follow system font size",
    description = "Scales Samsung Music's text with the phone's font size.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_MUSIC)
    dependsOn(textSizesPatch)

    execute {
        // Music's own text view scales its size by the font size only up to its "max font level" (130 % for list rows
        // and titles), whatever larger size the phone asks for. Its size is now set in sp, so it scales as other text.
        val init = mutableClassDefBy(ONE_UI_TEXT_VIEW).methods.firstOrNull {
            it.name == "<init>" && it.parameterTypes == listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I")
        } ?: throw PatchException("no $ONE_UI_TEXT_VIEW<init>(Context, AttributeSet, int)")
        val instructions = init.implementation!!.instructions.toList()
        val setTextSize = instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_VIRTUAL && (it as ReferenceInstruction).reference.toString() == SET_TEXT_SIZE
        }
        val scale = instructions.getOrNull(setTextSize - 1) as? TwoRegisterInstruction
        val call = instructions.getOrNull(setTextSize) as? FiveRegisterInstruction
        if (scale == null || scale.opcode != Opcode.MUL_FLOAT_2ADDR || call == null || call.registerE != scale.registerA ||
            call.registerD > 15
        ) {
            throw PatchException("$ONE_UI_TEXT_VIEW no longer sets its text size as size x capped font scale")
        }
        // size x scale (in dp) -> size (in sp); the unit register is not read after the call.
        init.replaceInstruction(setTextSize - 1, "const/4 v${call.registerD}, 0x2")
    }
}
