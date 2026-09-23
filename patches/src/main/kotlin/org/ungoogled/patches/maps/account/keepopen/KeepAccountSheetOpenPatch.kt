package org.ungoogled.patches.maps.account.keepopen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel

/**
 * The account sheet's custom-action tap: run the action, then ALWAYS answer
 * DISMISS (field a of the dismiss-policy enum; b is NO_DISMISS -- both read off
 * its static initializer), which closes the sheet.
 */
private object AccountSheetActionDismissFingerprint : Fingerprint(
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "Lcmia;", name = "e"),
        methodCall(opcode = Opcode.INVOKE_INTERFACE, definingClass = "Lcujo;", name = "a", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.SGET_OBJECT, definingClass = "Lcmly;", name = "a", location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_OBJECT, MatchAfterImmediately()),
    ),
    custom = { method, _ -> method.definingClass == "Lbqbr;" },
)

/**
 * Lets something of ours opened from the account sheet (the Customization
 * screen, the profile toast) leave the sheet open behind it: when
 * Shapes.SKIP_DISMISS is set, the tap answers NO_DISMISS once instead.
 * Every other row still closes the sheet as usual.
 */
internal val accountSheetNoDismissPatch = bytecodePatch(
    description = "Lets the account sheet stay open behind screens opened from it.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        AccountSheetActionDismissFingerprint.let { fp ->
            val dismiss = fp.instructionMatches[2].index
            val register = (fp.method.implementation!!.instructions[dismiss] as OneRegisterInstruction).registerA
            fp.method.addInstructionsWithLabels(
                dismiss,
                """
                    sget-boolean v$register, $SHAPES->SKIP_DISMISS:Z
                    if-eqz v$register, :dismiss
                    const/4 v$register, 0x0
                    sput-boolean v$register, $SHAPES->SKIP_DISMISS:Z
                    sget-object v$register, Lcmly;->b:Lcmly;
                    return-object v$register
                """,
                ExternalLabel("dismiss", fp.method.implementation!!.instructions[dismiss]),
            )
        }
    }
}

/** Shows the OneGoogle account sheet (the avatar tap runs it); the only place its dynamic-colour flag is set. */
private object AccountMenuShowerFingerprint : Fingerprint(
    name = "b",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(string("enableDynamicColors")),
)

/**
 * androidx Fragment.onDestroy -- the bare `mCalled = true`. Identified through
 * the fragment state manager's performDestroy: it clears mCalled, runs a
 * lambda whose switch case 14 calls this, then throws "... did not call
 * through to super.onDestroy()" if it is still false. Several other lifecycle
 * callbacks have the identical body, so the name is pinned to that finding.
 */
private object FragmentOnDestroyFingerprint : Fingerprint(
    definingClass = "Lbh;",
    name = "ai",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        literal(1),
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN, definingClass = "this", name = "O", location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_VOID, MatchAfterImmediately()),
    ),
)

/** The account sheet's Settings row: its case in the shared row-click dispatcher, from its first instruction. */
private object SettingsRowClickFingerprint : Fingerprint(
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", name = "a"),
        opcode(Opcode.MOVE_OBJECT, MatchAfterImmediately()),
        checkCast("Lolr;", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "Lolr;", location = MatchAfterImmediately()),
        string("SettingsActionSpecClicked", location = MatchAfterImmediately()),
    ),
)

@Suppress("unused")
val keepAccountSheetOpenPatch = bytecodePatch(
    name = "Keep account sheet open",
    description = "Returning from Settings or Customization, or tapping \"Your profile\", " +
        "leaves the account sheet open instead of dropping back to the map.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, accountSheetNoDismissPatch)

    execute {
        // Remember the sheet's shower every time it opens, so it can be re-shown.
        AccountMenuShowerFingerprint.method.addInstructions(0, "sput-object p0, $SHAPES->MENU:Ljava/lang/Object;")

        // Report every fragment destroy; Shapes re-shows the sheet when the Settings page goes away.
        FragmentOnDestroyFingerprint.method.addInstructions(0, "invoke-static { p0 }, $SHAPES->fragmentDestroyed(Ljava/lang/Object;)V")

        // The Settings row asks for the sheet back. At the case's own label, so the switch reaches it.
        SettingsRowClickFingerprint.let { fp ->
            val caseEntry = fp.instructionMatches.first().index
            fp.method.addInstructionsAtLabel(
                caseEntry,
                """
                    const/4 v0, 0x1
                    sput-boolean v0, $SHAPES->REOPEN_MENU:Z
                """,
            )
        }
        if (SettingsRowClickFingerprint.method.definingClass != "Lolq;") throw PatchException("Settings row is no longer in the shared dispatcher")
    }
}
