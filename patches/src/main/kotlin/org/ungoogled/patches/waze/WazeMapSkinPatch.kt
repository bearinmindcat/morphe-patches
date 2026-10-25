package org.ungoogled.patches.waze

import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.floatOption
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE
import java.util.Locale

/** A colour option as six hex digits, whatever the user typed ("#ebe7dd", "0xEBE7DD", "ebe7dd"). */
private fun hexColour(value: String?, what: String): String {
    val hex = value?.trim()?.removePrefix("#")?.removePrefix("0x")?.removePrefix("0X") ?: ""
    if (!Regex("[0-9a-fA-F]{6}").matches(hex)) throw PatchException("$what must be six hex digits, got \"$value\"")
    return hex.uppercase(Locale.ROOT)
}

/**
 * The old Waze mods' map skin, applied while Waze runs. Waze copies `res/skins/default/` out of its APK
 * into its data folder on its first start, and its map engine reads that copy; the extension rewrites the
 * copy from the APK's originals as Waze starts (Customization.applySkin). Nothing in the APK's assets is
 * edited: Morphe Manager 1.35 (patcher 1.15) writes folder entries for edited assets that crash Waze's map
 * engine, which is why this used to be off by default and skipped there.
 */
@Suppress("unused")
val wazeMapSkinPatch: BytecodePatch = bytecodePatch(
    name = "Map skin",
    description = "Bigger map labels, a wider arrow and a warm day background.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeCustomizationPatch)

    val fontHuge by intOption(key = "fontHuge", default = 18, title = "Map labels: huge", description = "Waze: 14.", required = true)
    val fontBig by intOption(key = "fontBig", default = 14, title = "Map labels: big", description = "Waze: 12.", required = true)
    val fontMedium by intOption(key = "fontMedium", default = 12, title = "Map labels: medium", description = "Waze: 10.", required = true)
    val fontSmall by intOption(key = "fontSmall", default = 10, title = "Map labels: small", description = "Waze: 8.", required = true)
    val arrowHead by floatOption(
        key = "arrowHead",
        default = 10.0f,
        title = "Navigation arrow head width",
        description = "Times the tail width. Waze: 3.0.",
        required = true,
    )
    val dayBackground by stringOption(
        key = "dayBackground",
        default = "ebe7dd",
        title = "Day map background (hex)",
        description = "Waze: fafcfa.",
        required = true,
    )

    execute {
        val day = hexColour(dayBackground, "the day background")
        val head = Math.round(arrowHead!! * 10)
        if (head !in 0..300) throw PatchException("the arrow head width must be 0 to 30")
        declareWazeToggle("mapSkin")
        declareWazeValue("mapSkin", "SKIN_FONT_HUGE", fontHuge!!)
        declareWazeValue("mapSkin", "SKIN_FONT_BIG", fontBig!!)
        declareWazeValue("mapSkin", "SKIN_FONT_MEDIUM", fontMedium!!)
        declareWazeValue("mapSkin", "SKIN_FONT_SMALL", fontSmall!!)
        declareWazeValue("mapSkin", "SKIN_ARROW_HEAD", head)
        declareWazeValue("mapSkin", "SKIN_DAY_BACKGROUND", day)
    }
}

/**
 * Bigger alert pins (issue #59). Waze 5.x draws its realtime alert pins with Godot's 2D renderer (the default;
 * the server value "Waze map use 3D realtime alerts" switches to a 3D scene), from three scenes in its
 * waze_map_scenes.pck: OverviewAlertsAnimation/SmallScene (the zoomed-out dot), MediumScene (the bubble) and
 * LargeScene (the large pin). Waze sets only each scene root's scale as the map zooms; the pin's own size is the
 * scale the scene's AnimationPlayer gives the root's children. Waze copies the pack to its data folder once and
 * loads that copy, so the extension multiplies those keys in the copy as Waze starts (Customization.applyPins,
 * GodotPins); the APK's assets are not edited. The scenes serve every kind of alert, so all of them grow.
 */
@Suppress("unused")
val wazeBiggerPinsPatch: BytecodePatch = bytecodePatch(
    name = "Bigger alert pins",
    default = false,
    description = "Bigger alert pins on the map.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeCustomizationPatch)

    val size by intOption(
        key = "size",
        default = 150,
        title = "Pin size (%)",
        description = "50–300. Waze: 100.",
        required = true,
    )

    execute {
        if (size!! !in 50..300) throw PatchException("the pin size must be 50 to 300 %")
        declareWazeToggle("biggerPins")
        declareWazeValue("biggerPins", "PIN_SIZE", size!!)
    }
}
