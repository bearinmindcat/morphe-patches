package org.ungoogled.patches.maps.ui.customization

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.maps.account.trim.trimAccountMenuPatch
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.replaceBlockAtLabel
import org.w3c.dom.Element

private const val ACTIVITY = "org.ungoogled.ui.CustomizationActivity"
private const val TITLE = "Customization"
/** drawable/ic_safer_shield_ic_outline_your_data and drawable/quantum_gm_ic_edit_vd_theme_24 in this build. */
private const val YOUR_DATA_ICON = 0x7f080866
private const val EDIT_ICON = 0x7f080bb4
/** string/og_privacy_advisor: "Your data in %s". */
private const val YOUR_DATA_TITLE = 0x7f142750

/** Declares the screen; it is launched by explicit class name from inside the app only. */
private val customizationManifestPatch = resourcePatch(
    description = "Declares the Customization screen.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:label", TITLE)
            activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.DayNight")
            application.appendChild(activity)
        }
    }
}

/**
 * The "Your data in Maps" row builder: icon, a localized "Your data in <app>"
 * title, and a click listener that is case 8 of the shared row dispatcher.
 * Trim account menu removes every call to it, so it is free to repurpose.
 */
private object YourDataRowFingerprint : Fingerprint(
    name = "a",
    returnType = "Lbrmi;",
    parameters = emptyList(),
    filters = listOf(literal(YOUR_DATA_ICON), literal(YOUR_DATA_TITLE)),
)

/** A sheet builder's Settings row -- always the last row -- followed by the list being built. */
private fun settingsRowFingerprint(returnType: String, parameters: List<String>) = Fingerprint(
    returnType = returnType,
    parameters = parameters,
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_STATIC, definingClass = "Lbsoa;", name = "aB"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, definingClass = "Lbwxy;", name = "i", location = MatchAfterImmediately()),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, definingClass = "Lbwxy;", name = "h", location = MatchAfterImmediately()),
    ),
)
private val modernSettingsRowFingerprint = settingsRowFingerprint("Lbrhh;", listOf("Lafmm;"))
private val legacySettingsRowFingerprint = settingsRowFingerprint("Lbrfb;", listOf("Z"))

/** Case 8 of the shared row dispatcher: log, then open the privacy advisor. */
private object PrivacyAdvisorClickFingerprint : Fingerprint(
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", name = "a"),
        fieldAccess(opcode = Opcode.SGET_OBJECT, definingClass = "Lcpdf;", name = "qw", location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_OBJECT, MatchAfterImmediately()),
        checkCast("Lolr;", location = MatchAfterImmediately()),
        methodCall(definingClass = "Lolr;", name = "g", location = MatchAfterImmediately()),
    ),
)

private fun MutableMethod.callsYourDataRow() = implementation!!.instructions.any {
    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> r.definingClass == "Lolr;" && r.name == "a" && r.returnType == "Lbrmi;" } == true
}

private fun BuilderInstruction.hasLabel() = location.labels.isNotEmpty()

@Suppress("unused")
val customizationScreenPatch = bytecodePatch(
    name = "Customization screen",
    description = "Adds a Customization row under Settings on the account sheet, with switches for the " +
        "patches here that can be turned back off inside the app. Also applies Trim account menu, " +
        "whose freed row builder it takes over.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, trimAccountMenuPatch, customizationManifestPatch)

    execute {
        // 1. Repurpose the freed "Your data in Maps" builder: edit icon, literal title.
        YourDataRowFingerprint.let { fp ->
            val method = fp.method
            val instructions = method.implementation!!.instructions
            val icon = fp.instructionMatches[0].index
            method.replaceInstruction(icon, "const v${(instructions[icon] as OneRegisterInstruction).registerA}, $EDIT_ICON")

            // The title is built as getString(og_privacy_advisor, new Object[]{appName}) over seven
            // straight-line instructions ending in the register handed to the title setter.
            val titleId = fp.instructionMatches[1].index
            val start = titleId - 4
            val end = titleId + 2
            val expected = listOf(
                Opcode.CONST_4, Opcode.NEW_ARRAY, Opcode.CONST_4, Opcode.APUT_OBJECT,
                Opcode.CONST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT,
            )
            if ((start..end).map { instructions[it].opcode } != expected) throw PatchException("row title construction changed shape")
            if ((start..end).any { instructions[it].hasLabel() }) throw PatchException("row title construction is a branch target")
            val title = (instructions[end] as OneRegisterInstruction).registerA
            method.replaceInstruction(start, "const-string v$title, \"$TITLE\"")
            method.removeInstructions(start + 1, end - start)
        }

        // 2. Add the row right after Settings in both of the sheet's builders.
        modernSettingsRowFingerprint.let { fp ->
            val method = fp.method
            if (method.callsYourDataRow()) throw PatchException("the modern builder still adds the Your data row")
            val instructions = method.implementation!!.instructions
            // Every row reaches the row builder through `move-object vN, <holder>; check-cast vN, Lolr;`.
            val holders = instructions.indices.filter { i ->
                i > 0 && instructions[i].opcode == Opcode.CHECK_CAST &&
                    ((instructions[i] as ReferenceInstruction).reference as TypeReference).type == "Lolr;" &&
                    instructions[i - 1].opcode == Opcode.MOVE_OBJECT
            }.map { (instructions[it - 1] as TwoRegisterInstruction).registerB }.toSet()
            val holder = holders.singleOrNull() ?: throw PatchException("row builder holder is ambiguous: $holders")
            val list = (instructions[fp.instructionMatches[2].index] as Instruction35c).registerC
            // The list's own build call overwrites this register right after, so it is free here.
            val scratch = (instructions[fp.instructionMatches.last().index + 1] as OneRegisterInstruction).registerA
            method.addInstructions(
                fp.instructionMatches.last().index,
                """
                    move-object v$scratch, v$holder
                    check-cast v$scratch, Lolr;
                    invoke-virtual { v$scratch }, Lolr;->a()Lbrmi;
                    move-result-object v$scratch
                    invoke-virtual { v$list, v$scratch }, Lbwxy;->i(Ljava/lang/Object;)V
                """,
            )
        }
        legacySettingsRowFingerprint.let { fp ->
            val method = fp.method
            if (method.callsYourDataRow()) throw PatchException("the legacy builder still adds the Your data row")
            val instructions = method.implementation!!.instructions
            val holders = instructions.filter {
                it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == "Lolr;"
            }.map { (it as OneRegisterInstruction).registerA }.toSet()
            val holder = holders.singleOrNull() ?: throw PatchException("row builder holder is ambiguous: $holders")
            val list = (instructions[fp.instructionMatches[2].index] as Instruction35c).registerC
            // The list's own build call overwrites this register right after.
            val scratch = (instructions[fp.instructionMatches.last().index + 1] as OneRegisterInstruction).registerA
            method.addInstructions(
                fp.instructionMatches.last().index,
                """
                    invoke-virtual { v$holder }, Lolr;->a()Lbrmi;
                    move-result-object v$scratch
                    invoke-virtual { v$list, v$scratch }, Lbwxy;->i(Ljava/lang/Object;)V
                """,
            )
        }

        // 3. Its tap opens the Customization screen, leaving the sheet open behind it
        //    (when Keep account sheet open is also applied; otherwise the flag is simply unread).
        PrivacyAdvisorClickFingerprint.let { fp ->
            if (fp.method.definingClass != "Lolq;") throw PatchException("privacy-advisor click is no longer in the shared dispatcher")
            fp.method.replaceBlockAtLabel(
                fp.instructionMatches.first().index,
                """
                    const/4 v1, 0x1
                    sput-boolean v1, $SHAPES->SKIP_DISMISS:Z
                    iget-object p0, p0, Lolq;->a:Ljava/lang/Object;
                    check-cast p0, Lolr;
                    iget-object p0, p0, Lolr;->a:Lnxb;
                    new-instance p1, Landroid/content/Intent;
                    invoke-direct { p1 }, Landroid/content/Intent;-><init>()V
                    invoke-virtual { p0 }, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                    move-result-object v0
                    const-string v1, "$ACTIVITY"
                    invoke-virtual { p1, v0, v1 }, Landroid/content/Intent;->setClassName(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;
                    invoke-virtual { p0, p1 }, Landroid/content/Context;->startActivity(Landroid/content/Intent;)V
                    return-void
                """,
            )
        }
    }
}
