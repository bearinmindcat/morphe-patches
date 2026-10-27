package org.ungoogled.patches.maps.saved

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import org.ungoogled.patches.maps.microg.MicrogSelection
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

/**
 * Declares the Timeline screen and its recorder: a location-type foreground
 * service. Maps already holds every permission it needs (fine location,
 * FOREGROUND_SERVICE_LOCATION, POST_NOTIFICATIONS); background location is not
 * one of them, because recording only ever starts with Maps in front.
 */
private val timelineManifestPatch = resourcePatch(description = "Declares the Timeline screen and recorder.") {
    execute {
        // microG Maps keeps it too: microG records no Timeline of its own (issue #73).
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

/**
 * Opens Google's Timeline: the account sheet's Your Timeline row and every other way in end here
 * (trace label "MapsActivityVeneerImpl.startTimeline"). Its class keeps the Maps activity in a field.
 */
private object GoogleTimelineFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("MapsActivityVeneerImpl.startTimeline")),
)

internal val localTimelinePatch = bytecodePatch(
    description = "Adds a Timeline to the Local saved screen: a record of where the phone has been, grouped into days " +
        "and visits, kept only on the phone, with GPX export. Recording is off until switched on there; it " +
        "shows a notification while it runs.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(localSavedPlacesPatch, timelineManifestPatch)

    execute {
        markPatched("timelinePatched")

        // microG Maps: Google's Timeline goes to microG, which records none -- turning it on only said
        // "Something went wrong" (issue #73) -- so every way into it opens this Timeline instead.
        if (MicrogSelection.builds(this, "Offline saved places")) {
            val open = GoogleTimelineFingerprint.method
            fun isActivity(type: String): Boolean {
                var t: String? = type
                repeat(16) {
                    if (t == "Landroid/app/Activity;") return true
                    t = classDefByOrNull(t ?: return false)?.superclass ?: return false
                }
                return false
            }
            val activity = classDefBy(open.definingClass).fields
                .filter { !com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) && isActivity(it.type) }
                .singleOrNull() ?: throw PatchException("${open.definingClass} keeps no single Maps activity")
            open.addInstructions(
                0,
                """
                    iget-object v0, p0, ${open.definingClass}->${activity.name}:${activity.type}
                    invoke-static { v0 }, Lorg/ungoogled/ui/TimelineActivity;->open(Landroid/content/Context;)V
                    return-void
                """,
            )
        }
    }
}
