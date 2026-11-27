package org.ungoogled.patches.onepluslauncher.misc

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.coloros.dropOplusTrackEvents
import org.ungoogled.patches.onepluslauncher.OnePlusLauncherConstants.COMPATIBILITY_ONEPLUS_LAUNCHER

@Suppress("unused")
val removeTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "Stops usage statistics to OPPO's statistics service on OnePlus phones.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ONEPLUS_LAUNCHER)
    dependsOn(enableNonOxygenOsPatch)

    execute {
        dropOplusTrackEvents()
    }
}
