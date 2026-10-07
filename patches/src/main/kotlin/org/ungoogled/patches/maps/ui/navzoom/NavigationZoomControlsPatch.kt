package org.ungoogled.patches.maps.ui.navzoom

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val navigationZoomControlsPatch = bytecodePatch(
    name = "Zoom controls in navigation",
    description = "Adds +, − and reset tiles during turn-by-turn that change the navigation zoom " +
        "while the camera keeps following the car.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, navigationCameraHookPatch)

    execute {
        // The tiles and the zoom hold are the extension's (Shapes); the camera hooks
        // they run on are navigationCameraHookPatch's.
        markPatched("navZoomPatched")
    }
}
