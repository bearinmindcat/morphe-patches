package org.ungoogled.patches.maps.home

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel

internal val hideExploreFeedPatch = bytecodePatch(
    description = "Hides the home tab's Explore feed sheet (\"Local vibe\"). Can be switched back on " +
        "on the Customization screen.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("hideExplorePatched")

        // The home fragment works out the sheet's first state (saved state, tab type, server config, a time
        // check) and every path converges on one value -- the last of them the EXPANDED fallback, `state =
        // Controller.DEFAULT` -- right before the fragment transition is built (a new-instance that is also the
        // time check's branch target). While the switch is on the state becomes HIDDEN there, whichever path ran.
        val state = SheetStateEnumFingerprint.classDef.type
        val clinit = SheetStateEnumFingerprint.method.implementation!!.instructions.toList()
        val hiddenAt = clinit.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "HIDDEN" }
        val hidden = clinit.drop(hiddenAt).firstNotNullOfOrNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { insn.opcode == Opcode.SPUT_OBJECT && it.definingClass == state }
        } ?: throw PatchException("the sheet state HIDDEN's field not found")
        val home = mutableClassDefBy(HomeFragmentIdFingerprint.classDef.type)
        val sites = home.methods.flatMap { m ->
            val code = m.implementation?.instructions?.toList() ?: return@flatMap emptyList()
            code.indices.filter { i ->
                i + 1 < code.size && code[i].opcode == Opcode.SGET_OBJECT && code[i + 1].opcode == Opcode.NEW_INSTANCE &&
                    ((code[i] as ReferenceInstruction).reference as FieldReference).let { it.type == state && it.definingClass != state }
            }.map { m to it }
        }
        val (method, fallback) = sites.singleOrNull() ?: throw PatchException("the home sheet's merged first state: found ${sites.size}")
        val code = method.implementation!!.instructions
        val stateRegister = (code[fallback] as OneRegisterInstruction).registerA
        val mergePoint = fallback + 1
        // The flag borrows the new-instance's register, which the merge point itself overwrites.
        val flag = (code[mergePoint] as OneRegisterInstruction).registerA
        if (flag == stateRegister || flag > 255) throw PatchException("the home sheet's merge point has no free register")
        method.addInstructionsAtLabel(mergePoint, "sget-boolean v$flag, $SHAPES->HIDE_EXPLORE:Z")
        method.addInstructionsWithLabels(
            mergePoint + 1,
            """
                if-eqz v$flag, :keep_state
                sget-object v$stateRegister, $hidden
            """,
            ExternalLabel("keep_state", method.implementation!!.instructions[mergePoint + 1]),
        )
    }
}
