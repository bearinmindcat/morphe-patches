package org.ungoogled.patches.maps.account.keepopen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.maps.account.CustomActionToStringFingerprint
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.customization.accountSheet
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel

/** The account sheet's dismiss policy, an enum of DISMISS and NO_DISMISS. */
private object DismissPolicyFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(string("DISMISS"), string("NO_DISMISS")),
)

/** Shows the OneGoogle account sheet (the avatar tap runs it); the only place its dynamic-colour flag is set. */
private object AccountMenuShowerFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(string("enableDynamicColors")),
)

/** The Settings pages (the new one and the legacy one): Maps' only fragments with a mirroring settings helper. */
private object SettingsPageFingerprint : Fingerprint(
    filters = listOf(string("mirroringConfigurationSettingsUiHelper")),
)

/** The account sheet's Settings row: its case in the shared row-click dispatcher, from its first instruction. */
private object SettingsRowClickFingerprint : Fingerprint(
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this"),
        opcode(Opcode.MOVE_OBJECT, MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, MatchAfterImmediately()),
        opcode(Opcode.IGET_OBJECT, MatchAfterImmediately()),
        string("SettingsActionSpecClicked", location = MatchAfterImmediately()),
    ),
)

private fun Any.fieldRef() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Any.stringRef() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** The static field [constant] is stored in by an enum's static initializer. */
private fun ClassDef.enumField(clinit: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>, constant: String): FieldReference {
    val at = clinit.indexOfFirst { it.stringRef() == constant }
    return clinit.drop(at).firstOrNull { it.opcode == Opcode.SPUT_OBJECT }?.fieldRef()?.takeIf { it.definingClass == type }
        ?: throw PatchException("$type: $constant's field not found")
}

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
        val policy = DismissPolicyFingerprint.classDef
        val clinit = DismissPolicyFingerprint.method.implementation!!.instructions.toList()
        val dismiss = policy.enumField(clinit, "DISMISS")
        val noDismiss = policy.enumField(clinit, "NO_DISMISS")
        // The action's click handler: the field its toString prints after "onClick=".
        val toString = CustomActionToStringFingerprint.method.implementation!!.instructions.toList()
        val onClick = toString.drop(CustomActionToStringFingerprint.instructionMatches.last().index)
            .firstOrNull { it.opcode == Opcode.IGET_OBJECT }?.fieldRef()
            ?: throw PatchException("the account sheet action's click handler not found")
        // The tap: run the handler, then always answer DISMISS.
        val tap = Fingerprint(
            filters = listOf(
                fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = onClick.definingClass, name = onClick.name, type = onClick.type),
                methodCall(opcode = Opcode.INVOKE_INTERFACE, definingClass = onClick.type, location = MatchAfterImmediately()),
                fieldAccess(opcode = Opcode.SGET_OBJECT, definingClass = dismiss.definingClass, name = dismiss.name, location = MatchAfterImmediately()),
                opcode(Opcode.RETURN_OBJECT, MatchAfterImmediately()),
            ),
        )
        val matches = tap.matchAll()
        val match = matches.singleOrNull() ?: throw PatchException("account sheet action tap: found ${matches.size}")
        val method = match.method
        val at = match.instructionMatches[2].index
        val register = (method.implementation!!.instructions[at] as OneRegisterInstruction).registerA
        method.addInstructionsWithLabels(
            at,
            """
                sget-boolean v$register, $SHAPES->SKIP_DISMISS:Z
                if-eqz v$register, :dismiss
                const/4 v$register, 0x0
                sput-boolean v$register, $SHAPES->SKIP_DISMISS:Z
                sget-object v$register, ${noDismiss.definingClass}->${noDismiss.name}:${noDismiss.type}
                return-object v$register
            """,
            ExternalLabel("dismiss", method.implementation!!.instructions[at]),
        )
    }
}

/** Points a `static String <name>() { return "" }` of Shapes at [value]. */
private fun BytecodePatchContext.answerShapes(name: String, value: String) {
    val getter = mutableClassDefBy(SHAPES).methods.singleOrNull {
        it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("$SHAPES->$name() not found")
    val first = getter.implementation!!.instructions.first()
    if (first.opcode != Opcode.CONST_STRING) throw PatchException("$name() no longer starts with const-string")
    getter.replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$value\"")
}

internal val keepAccountSheetOpenPatch = bytecodePatch(
    description = "Returning from Settings or Customization, or tapping \"Your profile\", " +
        "leaves the account sheet open instead of dropping back to the map.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, accountSheetNoDismissPatch)

    execute {
        // Remember the sheet's shower every time it opens, so it can be re-shown (by this name).
        AccountMenuShowerFingerprint.method.let { shower ->
            shower.addInstructions(0, "sput-object p0, $SHAPES->MENU:Ljava/lang/Object;")
            answerShapes("menuShowMethod", shower.name)
        }

        // Each Settings page's onDestroy (the method that first calls the same one of Fragment, then compares
        // the account to the one it was created with) reports it; the sheet comes back if its row opened the page.
        val pages = SettingsPageFingerprint.matchAll().map { it.classDef.type }.distinct()
        if (pages.size != 2) throw PatchException("expected the new and the legacy Settings page, found ${pages.size}")
        for (page in pages) {
            val onDestroy = mutableClassDefBy(page).methods.filter { m ->
                val code = m.implementation?.instructions?.toList() ?: return@filter false
                val first = code.firstOrNull()
                m.returnType == "V" && m.parameterTypes.isEmpty() && first?.opcode == Opcode.INVOKE_SUPER &&
                    ((first as ReferenceInstruction).reference as MethodReference).name == m.name &&
                    code.any { it.stringRef() == "accountIdAtCreation" }
            }
            val method = onDestroy.singleOrNull() ?: throw PatchException("$page: onDestroy found ${onDestroy.size} times")
            method.addInstructions(1, "invoke-static { p0 }, $SHAPES->fragmentDestroyed(Ljava/lang/Object;)V")
        }

        // The Settings row asks for the sheet back. At the case's own label, so the switch reaches it.
        val holder = accountSheet().holder
        SettingsRowClickFingerprint.let { fp ->
            val caseEntry = fp.instructionMatches.first().index
            val code = fp.method.implementation!!.instructions
            val cast = (code[caseEntry + 2] as ReferenceInstruction).reference.toString()
            if (cast != holder) throw PatchException("Settings row click casts to $cast, not the account sheet's $holder")
            // The case's first instruction reads the dispatcher (`iget-object vA, vB, this.a`, vB often vA); the
            // move-object after it writes its destination before reading it, so that register is free.
            val receiver = (code[caseEntry] as TwoRegisterInstruction).registerB
            val scratch = (code[caseEntry + 1] as TwoRegisterInstruction).registerA
            if (scratch == receiver || scratch > 15) throw PatchException("Settings row click has no free register")
            fp.method.addInstructionsAtLabel(
                caseEntry,
                """
                    const/4 v$scratch, 0x1
                    sput-boolean v$scratch, $SHAPES->REOPEN_MENU:Z
                """,
            )
        }
    }
}
