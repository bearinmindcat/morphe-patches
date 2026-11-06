package org.ungoogled.patches.waze

import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

private val MAP_THEMES = mapOf("Stock" to "stock", "Black" to "black", "Dark grey" to "grey")

/**
 * A dark map without a dark app (issue #126). Only the map's skin changes, written into Waze's own copy of it while
 * Waze runs (Customization.applySkin, as for Map skin and Full black's Black night map); Waze's menus, sheets and buttons
 * keep their colours. A dark day map is Waze's night skin on black or dark grey. Each choice that is not Stock wins over
 * Map skin's day background and Full black's Black night map; Stock leaves the map to them, or to Waze.
 */
@Suppress("unused")
val wazeMapThemePatch: BytecodePatch = bytecodePatch(
    name = "Map theme",
    description = "Black or dark grey map by day and night. Menus keep their colours.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeCustomizationPatch)

    val day by stringOption(
        key = "day",
        default = "black",
        values = MAP_THEMES,
        title = "Day map",
        description = "Black and dark grey use Waze's night colours.",
        required = true,
    )
    val night by stringOption(
        key = "night",
        default = "black",
        values = MAP_THEMES,
        title = "Night map",
        description = "Stock leaves it to Waze, or to Full black's Black night map.",
        required = true,
    )

    execute {
        for ((what, v) in listOf("day map" to day, "night map" to night)) {
            if (v !in MAP_THEMES.values) throw PatchException("the $what must be one of ${MAP_THEMES.values}, got \"$v\"")
        }
        declareWazeToggle("mapTheme")
        declareWazeValue("mapTheme", "MAP_THEME_DAY", day!!)
        declareWazeValue("mapTheme", "MAP_THEME_NIGHT", night!!)
    }
}
