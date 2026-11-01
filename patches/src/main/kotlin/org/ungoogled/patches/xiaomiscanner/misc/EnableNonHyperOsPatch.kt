package org.ungoogled.patches.xiaomiscanner.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.xiaomi.redirectMiuiMembers
import org.ungoogled.patches.xiaomiscanner.XiaomiScannerConstants.COMPATIBILITY_XIAOMI_SCANNER

internal const val XIAOMI_SCANNER_EXTENSION = "extensions/xiaomiscanner.mpe"

/** AppUtil.isRightVersion(): true only when ro.miui.ui.version.name holds a MIUI version of 8 or newer. */
private object RightVersionFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("ro.miui.ui.version.name", "current miui version is "),
)

/**
 * Scanner refuses to start ("Update AI Scanner to the latest version to be able to use it", then closes) unless the
 * phone reports MIUI 8 or newer; off HyperOS the property is empty. The version check now passes.
 */
private val versionCheckPatch = bytecodePatch(
    description = "Skips Scanner's MIUI version check.",
) {
    execute {
        RightVersionFingerprint.method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
}

/** BuildHelper.getOAIDId(Context): HyperOS's OAID through com.android.id.impl.IdProviderImpl, cached. */
private object OaidFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("com.android.id.impl.IdProviderImpl", "getOAID", "getOAIDId exception"),
)

/**
 * Off HyperOS Scanner's OAID lookup answers null, and its server requests put that id into forms that refuse null:
 * text QR codes ended in "This request timed out" and object recognition in nothing. The lookup now answers "" (no
 * OAID) where the phone has none.
 */
private val oaidPatch = bytecodePatch(
    description = "Answers \"no OAID\" instead of null off HyperOS.",
) {
    extendWith(XIAOMI_SCANNER_EXTENSION)

    execute {
        OaidFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0}, Lorg/ungoogled/xiaomiscanner/Oaid;->get(Landroid/content/Context;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """,
        )
    }
}

/** Sends Scanner's uses of HyperOS's additions to Android classes through Miui (see redirectMiuiMembers). */
private val miuiMembersPatch = bytecodePatch(
    description = "Answers HyperOS's additions to Android classes where the phone has none.",
) {
    extendWith(XIAOMI_SCANNER_EXTENSION)

    execute {
        val (calls, fields) = redirectMiuiMembers(
            "Lorg/ungoogled/xiaomiscanner/Miui;", "Lorg/ungoogled/xiaomiscanner/MiuiShims;",
        )
        java.util.logging.Logger.getLogger("XiaomiScanner").info("HyperOS members: $calls calls, $fields fields")
        // Measured against 15.9.78: 6 calls (miuix's ActivityOptions transitions, Context.getContentResolverForUser).
        if (calls < 6) throw PatchException("only $calls HyperOS calls found")
    }
}

/**
 * Off HyperOS Scanner dies at once on miui.os.Build (a HyperOS class), once past that asks for a MIUI update, and dies
 * again whenever it goes to the background (its own copy of miui.process.ProcessManager needs HyperOS's
 * android.os.spc.PressureStateSettings). The extension carries stand-ins for the 8 HyperOS classes it uses (generated
 * by .github/scripts/xiaomi-standins); on a Xiaomi phone the framework's own classes load first, so they never run
 * there.
 */
@Suppress("unused")
val enableNonHyperOsPatch = bytecodePatch(
    name = "Enable non-HyperOS ROM support",
    description = "Makes Xiaomi Scanner usable on non-Xiaomi ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_XIAOMI_SCANNER)
    dependsOn(versionCheckPatch, oaidPatch, miuiMembersPatch)
    extendWith(XIAOMI_SCANNER_EXTENSION)
}
