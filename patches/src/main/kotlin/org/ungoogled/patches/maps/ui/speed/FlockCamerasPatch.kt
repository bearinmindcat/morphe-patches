package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val flockCamerasPatch = bytecodePatch(
    name = "Flock cameras",
    description = "Puts Flock and other license plate reader cameras on the map, from OpenStreetMap -- the cameras " +
        "DeFlock's volunteers map -- and chimes and shows a note when one is coming up ahead. Off until turned on " +
        "in Waze & external data source, a row on the account sheet, because it downloads the ones near you from an " +
        "OpenStreetMap server, over the same area as the speed cameras.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // Downloads, location, alerts and pins are the speed cameras' (SpeedCameras), as a second layer.
    dependsOn(speedCamerasPatch)

    execute {
        markPatched("flockCamerasPatched")
    }
}
