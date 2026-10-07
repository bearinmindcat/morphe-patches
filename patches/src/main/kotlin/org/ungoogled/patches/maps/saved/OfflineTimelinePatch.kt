package org.ungoogled.patches.maps.saved

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element
import org.ungoogled.patches.maps.microg.MicrogSelection

/**
 * Declares the Timeline screen and its recorder: a location-type foreground
 * service. Maps already holds every permission it needs (fine location,
 * FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS); background location is not
 * one of them, because recording only ever starts with Maps in front.
 */
private val timelineManifestPatch = resourcePatch(description = "Declares the Timeline screen and recorder.") {
    execute {
        if (MicrogSelection.replaces(this, "Offline saved places")) return@execute
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", "org.ungoogled.ui.TimelineActivity")
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:label", "Timeline")
            activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.DayNight")
            application.appendChild(activity)
            val service = manifest.createElement("service")
            service.setAttribute("android:name", "org.ungoogled.ui.TimelineService")
            service.setAttribute("android:exported", "false")
            service.setAttribute("android:foregroundServiceType", "location")
            application.appendChild(service)
        }
    }
}

internal val localTimelinePatch = bytecodePatch(
    description = "Adds a Timeline to the Local saved screen: a record of where the phone has been, grouped into days " +
        "and visits, kept only on the phone, with GPX export. Recording is off until switched on there; it " +
        "shows a notification while it runs.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(localSavedPlacesPatch, timelineManifestPatch)

    execute {
        if (MicrogSelection.replaces(this, "Offline saved places")) return@execute
        markPatched("timelinePatched")
    }
}
