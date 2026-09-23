package org.ungoogled.patches.maps.account

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val hideSectionTitlePatch = bytecodePatch(
    name = "Hide section title",
    description = "Removes the \"More from this app\" label from the account sheet.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        SectionTitleBinderFingerprint.method.apply {
            // Hide the TextView right after its text is set. Its register is the
            // receiver of setText; the argument register is dead afterwards, so
            // it carries the GONE constant.
            val setTextIndex = SectionTitleBinderFingerprint.instructionMatches[3].index
            val setText = getInstruction<FiveRegisterInstruction>(setTextIndex)
            val textView = setText.registerC
            val scratch = setText.registerD

            addInstructions(
                setTextIndex + 1,
                """
                    const/16 v$scratch, 0x8
                    invoke-virtual { v$textView, v$scratch }, Landroid/view/View;->setVisibility(I)V
                """,
            )
        }
    }
}
