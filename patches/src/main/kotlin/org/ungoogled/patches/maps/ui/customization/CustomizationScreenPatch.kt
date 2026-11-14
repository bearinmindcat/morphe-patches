package org.ungoogled.patches.maps.ui.customization

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.mapsId
import org.ungoogled.patches.maps.ui.screenHostPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

private const val ACTIVITY = "org.ungoogled.ui.CustomizationActivity"
private const val TITLE = "Customization"
/** The row's click listener: opens the Customization screen with the sheet left open behind it. */
private const val OPEN = "Lorg/ungoogled/ui/CustomizationActivity\$Open;"

/** Declares the screen; it is launched by explicit class name from inside the app only. */
private val customizationManifestPatch = resourcePatch(
    description = "Declares the Customization screen.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:label", TITLE)
            activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.DayNight")
            application.appendChild(activity)
        }
    }
}

@Suppress("unused")
val customizationScreenPatch = bytecodePatch(
    name = "Customization screen",
    description = "Adds a Customization row under Settings on the account sheet, with switches for the " +
        "patches here that can be turned back off inside the app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, screenHostPatch, customizationManifestPatch)

    execute {
        // A row of our own on the sheet's row holder, built the way Maps builds "Your data in Maps",
        // right after Settings in both of the sheet's builders. The sheet's own rows are left alone --
        // this used to take that row's builder over, which forced Trim account menu on signed-in users
        // who want those rows (issue #24). Trim account menu removes rows one by one, never this one.
        accountSheet().apply {
            val editIcon = mapsId(ResourceType.DRAWABLE, "quantum_gm_ic_edit_vd_theme_24")
            addRowBuilder("uaCustomizationRow", TITLE, editIcon, OPEN, listenerTakesContext = true)
            placeRow("uaCustomizationRow")
        }
    }
}
