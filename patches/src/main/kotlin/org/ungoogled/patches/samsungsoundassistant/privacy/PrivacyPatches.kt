package org.ungoogled.patches.samsungsoundassistant.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.samsung.hideDiagnosticsAgent
import org.ungoogled.patches.samsungsoundassistant.SamsungSoundAssistantConstants.COMPATIBILITY_SAMSUNG_SOUND_ASSISTANT

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Turns off Samsung Analytics and crash reports.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_SOUND_ASSISTANT)

    execute {
        // Samsung Analytics: the tracker is never ready, so it neither starts nor sends.
        AnalyticsTrackerReadyFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        // DiagMon (crash reports): the diagnostics agent reads as not installed, the SDK's own "not supported" case, so
        // no crash handler is set and no report is made. The app's start-up has one lookup inlined.
        val lookups = hideDiagnosticsAgent()
        // Measured against 8.1.00.6: 3 (the SDK's version check, its crash handler and the app's start-up).
        if (lookups < 2) throw PatchException("only $lookups diagnostics agent lookups found")
        java.util.logging.Logger.getLogger("SoundAssistant").info("Diagnostics agent lookups: $lookups")
    }
}
