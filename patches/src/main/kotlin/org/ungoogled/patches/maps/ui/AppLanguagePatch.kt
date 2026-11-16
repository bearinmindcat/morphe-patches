package org.ungoogled.patches.maps.ui

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22t
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val APP_LANGUAGE = "Lorg/ungoogled/ui/AppLanguage;"

/** Maps' dark mode controller handing its theme (Light / Dark / as the system) to the app. */
private object DarkModeOnDelegateFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("DarkModeControllerImpl.setDarkModeStateOnDelegate")),
)

/**
 * Issue #198: Maps' theme goes through AppCompat instead of Android's per-app night mode, which kept the app language
 * from being saved (see AppLanguage). On Android 12+ the controller asks `SDK_INT >= 31 && uiModeManager != null` before
 * `uiModeManager.setApplicationNightMode(mode)`, else it sets AppCompat's default night mode; the SDK it compares is
 * made 0 there, so it always takes AppCompat's way. The Activity override carries Maps' night bits to the screens.
 */
internal val appLanguagePatch = bytecodePatch(
    description = "Keeps the app language chosen in Maps after a reboot.",
) {
    dependsOn(sharedExtensionPatch, applicationStartHookPatch)

    execute {
        DarkModeOnDelegateFingerprint.method.apply {
            val code = implementation!!.instructions
            val setter = code.indexOfFirst { insn ->
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.parameterTypes?.map { it.toString() } ==
                    listOf("Landroid/app/UiModeManager;", "I") || ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.name == "setApplicationNightMode"
            }
            if (setter < 0) throw PatchException("Maps no longer sets Android's per-app night mode")
            val sdk = (setter downTo 0).firstOrNull { i ->
                code[i].opcode == Opcode.SGET && ((code[i] as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == "Landroid/os/Build\$VERSION;" && it.name == "SDK_INT"
                }
            } ?: throw PatchException("Maps' per-app night mode is no longer behind an SDK check")
            val register = (code[sdk] as OneRegisterInstruction).registerA
            val compare = (sdk + 1 until setter).firstOrNull { i ->
                code[i].opcode == Opcode.IF_LT && (code[i] as Instruction22t).registerA == register
            } ?: throw PatchException("Maps' SDK check is no longer `if (SDK_INT < 31) AppCompat`")
            val limit = (sdk + 1 until compare).firstNotNullOfOrNull { (code[it] as? NarrowLiteralInstruction)?.narrowLiteral }
            if (limit != 31) throw PatchException("Maps' SDK check compares with $limit, not 31")
            // the SDK value is read for this test only
            replaceInstruction(sdk, "const/16 v$register, 0x0")
        }
        // AppLanguage.patched() -> true
        mutableClassDefBy(APP_LANGUAGE).methods.single { it.name == "patched" && it.parameterTypes.isEmpty() }.let { marker ->
            val first = marker.implementation!!.instructions.first()
            if (first.opcode != Opcode.CONST_4) throw PatchException("$APP_LANGUAGE->patched() no longer starts with const/4")
            marker.replaceInstruction(0, "const/4 v${(first as OneRegisterInstruction).registerA}, 0x1")
        }
    }
}
