package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import org.ungoogled.patches.maps.ui.customization.accountSheet
import org.ungoogled.patches.maps.ui.mapsId
import org.ungoogled.patches.maps.ui.customization.customizationScreenPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch

/** The account sheet row's listener: opens Waze & external data source with the sheet left open behind it. */
private const val OPEN_WAZE = "Lorg/ungoogled/ui/CustomizationActivity\$OpenWaze;"
/** drawable/gs_directions_car_vd_theme_24: a car, tinted like the sheet's other icons. */
private val CAR_ICON get() = mapsId(ResourceType.DRAWABLE, "gs_directions_car_vd_theme_24")

/**
 * A "Waze & external data source" row on the account sheet -- the speeding chime and speed cameras -- under
 * Power Saving Options and above Local saved. It opens a page of the Customization screen.
 * Shared, so either patch brings it.
 */
internal val wazeFeaturesRowPatch = bytecodePatch(
    description = "Adds the Waze & external data source row to the account sheet.",
) {
    dependsOn(sharedExtensionPatch, customizationScreenPatch)

    // In finalize, after every patch has run, so the sheet's other rows of ours are in place: under Power
    // Saving Options' row when it is there (it always goes right after Customization), else right under
    // Customization's -- Customization, Power Saving Options, Waze & external data source, then Local
    // saved (added during execute, right after Customization), whichever finalize runs first.
    finalize {
        accountSheet().apply {
            addRowBuilder("uaWazeRow", "Waze & external data source", CAR_ICON, OPEN_WAZE, listenerTakesContext = true)
            placeRow("uaWazeRow", after = listOf("uaPowerRow", "uaCustomizationRow"))
        }
    }
}
