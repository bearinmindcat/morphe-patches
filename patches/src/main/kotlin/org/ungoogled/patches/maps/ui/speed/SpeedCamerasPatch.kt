package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.navzoom.navigationCameraHookPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val speedCamerasPatch = bytecodePatch(
    name = "Speed cameras",
    description = "Chimes and shows a note when a speed or red light camera is coming up ahead, and puts the " +
        "cameras on the map, from OpenStreetMap. Off until turned on in Waze Features, a row on the account " +
        "sheet, because it downloads the cameras near you from an OpenStreetMap server -- an area you choose there, " +
        "about 25 to 175 km across.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // The settings are read at every Activity (Shapes.wrap) and guidance start and end come from the
    // navigation camera hook. The pins go through Local saved's map markers, so they need that patch.
    dependsOn(sharedExtensionPatch, activityContextHookPatch, navigationCameraHookPatch, wazeFeaturesRowPatch)

    execute {
        markPatched("speedCamerasPatched")
    }
}
