package org.ungoogled.patches.maps.ui.refreshrate

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val highRefreshRatePatch = bytecodePatch(
    name = "120 refresh rate",
    description = "Lifts the 60 Hz limit Maps puts on itself, on the app and on the map, so it can run at " +
        "your screen's full refresh rate (such as 120 Hz). Uses more battery, most of all while navigating. " +
        "Off by default: switch it on on the Customization screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // HIGH_REFRESH is refreshed from the Customization switch at every Activity attach.
    dependsOn(sharedExtensionPatch, activityContextHookPatch, frameRateHookPatch)

    execute {
        // The window, the map's target and adaptive frame rate all go through RefreshRate
        // (frameRateHookPatch); this marker is what lets the switch turn it on.
        markPatched("highRefreshPatched")
    }
}
