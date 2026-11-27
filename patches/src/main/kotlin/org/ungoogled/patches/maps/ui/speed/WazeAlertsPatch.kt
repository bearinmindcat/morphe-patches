package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import org.ungoogled.patches.maps.saved.mapMarkersPatch
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.navzoom.navigationCameraHookPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/**
 * Start Waze with Maps finds Waze by its bridge's wake-up action (issue #207), so a Waze under any package name can
 * be started: Android 11+ shows Maps only the services it declares an interest in. Stock Maps already lists
 * com.waze, which covers root mount installs (stock manifests, stock package names).
 */
private val wazeVisibilityPatch = resourcePatch(description = "Lets Maps find Waze under any package name.") {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val queries = (0 until manifest.getElementsByTagName("queries").length)
                .map { manifest.getElementsByTagName("queries").item(it) as Element }
                .firstOrNull { it.parentNode == root }
                ?: manifest.createElement("queries").also { root.insertBefore(it, root.firstChild) }
            val intent = manifest.createElement("intent")
            intent.appendChild(manifest.createElement("action").apply { setAttribute("android:name", "org.ungoogled.waze.WAKE") })
            queries.appendChild(intent)
        }
    }
}

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
    dependsOn(sharedExtensionPatch, activityContextHookPatch, navigationCameraHookPatch, wazeFeaturesRowPatch, mapMarkersPatch, navigationMuteHookPatch, wazeVisibilityPatch)

    execute {
        markPatched("wazeAlertsPatched")
    }
}
