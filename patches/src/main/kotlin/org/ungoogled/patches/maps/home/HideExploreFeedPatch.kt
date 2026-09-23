package org.ungoogled.patches.maps.home

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel

@Suppress("unused")
val hideExploreFeedPatch = bytecodePatch(
    name = "Hide explore feed",
    description = "Hides the home tab's Explore feed sheet (\"Local vibe\"). Can be switched back on " +
        "on the Customization screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("hideExplorePatched")

        // While the switch is on, overwrite v9 with the HIDDEN enum constant at the
        // point every path that could have set it converges, so no matter which
        // path ran, the sheet starts hidden. That point is a branch target (a time
        // check jumps to it), so the check goes AT its label: an earlier version put
        // the override in front of the label, on the fall-through path only, and the
        // sheet stayed visible whenever the time check jumped past it.
        ExploreSheetStateFingerprint.method.apply {
            val mergePoint = ExploreSheetStateFingerprint.instructionMatches.last().index
            val merged = implementation!!.instructions[mergePoint]
            // The flag borrows v1, which the merge point itself overwrites.
            if (merged.opcode != Opcode.NEW_INSTANCE || (merged as OneRegisterInstruction).registerA != 1) {
                throw PatchException("explore sheet merge point no longer starts by overwriting v1")
            }
            addInstructionsAtLabel(mergePoint, "sget-boolean v1, $SHAPES->HIDE_EXPLORE:Z")
            addInstructionsWithLabels(
                mergePoint + 1,
                """
                    if-eqz v1, :keep_state
                    sget-object v9, Lpfi;->a:Lpfi;
                """,
                ExternalLabel("keep_state", implementation!!.instructions[mergePoint + 1]),
            )
        }
    }
}
