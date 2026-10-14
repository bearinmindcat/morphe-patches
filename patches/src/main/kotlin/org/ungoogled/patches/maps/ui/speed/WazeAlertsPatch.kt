package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.saved.mapMarkersPatch
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.navzoom.navigationCameraHookPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val wazeAlertsPatch = bytecodePatch(
    name = "Waze alerts",
    description = "Shows the alerts a Waze on the same phone is tracking -- police to begin with, and any of " +
        "crashes, hazards, traffic jams, closures and speed cameras -- as pins on the map, with a chime and a " +
        "note as one comes up ahead. Off until turned on in Waze & external data source. Waze has to be patched with Share " +
        "alerts with Maps, signed in and open; in the background is enough.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // The settings are read at every Activity (Shapes.wrap), which also registers the receiver; guidance
    // start and end come from the navigation camera hook. The pins are drawn as Maps' personal places
    // (mapMarkersPatch).
    dependsOn(sharedExtensionPatch, activityContextHookPatch, navigationCameraHookPatch, wazeFeaturesRowPatch, mapMarkersPatch)

    execute {
        markPatched("wazeAlertsPatched")
    }
}
