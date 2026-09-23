package org.ungoogled.patches.maps.home

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val hideNavigationTabsPatch = bytecodePatch(
    name = "Hide navigation tabs",
    description = "Hides the Explore / Contribute / You strip at the bottom of the home screen. Can be " +
        "switched back on on the Customization screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("hideTabsPatched")

        // Right after the strip's view holder is stored, hide its root view while
        // the switch is on. The map's bottom inset follows the strip's own laid-out
        // height, so nothing is left reserved for it. v2 is free here: both paths
        // on from this point write it before reading it.
        //
        // The skip target is bound to the real instruction that follows, via
        // addInstructionsWithLabels. An earlier version wrote it as a label inside
        // the snippet passed to plain addInstructions, which never rebinds such a
        // label: the branch kept its snippet-relative address (0xb), landed in the
        // middle of an instruction, and the verifier rejected the home screen's
        // whole class, so Maps crashed on launch.
        val method = BottomTabStripBindFingerprint.method
        val insertAt = BottomTabStripBindFingerprint.instructionMatches.last().index + 1
        method.addInstructionsWithLabels(
            insertAt,
            """
                sget-boolean v2, $SHAPES->HIDE_TABS:Z
                if-eqz v2, :tabs_done
                invoke-virtual {v1}, Lbzok;->a()Landroid/view/View;
                move-result-object v2
                if-eqz v2, :tabs_done
                const/16 v1, 0x8
                invoke-virtual {v2, v1}, Landroid/view/View;->setVisibility(I)V
            """,
            ExternalLabel("tabs_done", method.implementation!!.instructions[insertAt]),
        )
    }
}
