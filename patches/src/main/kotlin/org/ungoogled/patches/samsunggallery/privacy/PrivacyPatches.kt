package org.ungoogled.patches.samsunggallery.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.samsung.blankArguments
import org.ungoogled.patches.samsung.hideDiagnosticsAgent
import org.ungoogled.patches.samsung.packageManagerCall
import org.ungoogled.patches.samsunggallery.SamsungGalleryConstants.COMPATIBILITY_SAMSUNG_GALLERY

/** Samsung's policy service (SCPM), by its content provider's authority. */
private const val SCPM_POLICY = "com.samsung.android.scpm.policy"

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Turns off Samsung Analytics and crash reports.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_GALLERY)

    execute {
        // Samsung Analytics: the tracker is never ready, so it neither starts nor sends.
        AnalyticsTrackerReadyFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        // DiagMon (crash, ANR and internal-error reports, with logcat attached): the diagnostics agent reads as not
        // installed, the SDK's own "not supported" case, so Gallery's crash logger is never configured and reports
        // nothing. Gallery's DiagMon set-up has one lookup inlined.
        val lookups = hideDiagnosticsAgent()
        // Measured against 15.9.01.7: 2 (the SDK's version check and Gallery's DiagMon set-up).
        if (lookups < 2) throw PatchException("only $lookups diagnostics agent lookups found")
        java.util.logging.Logger.getLogger("SamsungGallery").info("Diagnostics agent lookups: $lookups")
    }
}

@Suppress("unused")
val stopRemoteUpdatesPatch = bytecodePatch(
    name = "Stop remote updates",
    description = "Ignores policies pushed from Samsung's servers.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_GALLERY)

    execute {
        // Samsung's policy service (SCPM) delivers Gallery's server-side policies through this one receiver; the one
        // Gallery reads is the list of apps a picture's "Go to source" link may open (the built-in list stays).
        ScpmPolicyReceiverFingerprint.method.addInstructions(0, "return-void")

        // Gallery registers for those policies at start-up when the service is there: it now reads as absent, the
        // path Gallery takes off One UI, so nothing is requested.
        val checks = ScpmRegistrationFingerprint.method.blankArguments(SCPM_POLICY, packageManagerCall("resolveContentProvider"))
        if (checks != 1) throw PatchException("expected one policy service check before registering, found $checks")
    }
}
