package org.ungoogled.patches.waze

import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/**
 * Black night map, applied while Waze runs: the extension writes a black background into Waze's own copy
 * of its night skins (Customization.applySkin), as for Map skin -- the APK's assets are not edited. Here
 * it is only listed, with a switch, when Full black's option asks for it.
 */
private val wazeBlackNightMapPatch: BytecodePatch = bytecodePatch {
    dependsOn(wazeCustomizationPatch)
    execute {
        if (wazeFullBlackPatch.options["blackMap"]?.value == true) declareWazeToggle("blackNightMap")
    }
}

/** A colour is dark enough to be a dark-mode surface: every channel at or under 0x40. */
private fun darkNeutral(argb: Long): Boolean {
    val r = (argb shr 16) and 0xffL
    val g = (argb shr 8) and 0xffL
    val b = argb and 0xffL
    return (argb ushr 24) >= 0xf0L && maxOf(r, g, b) <= 0x40L
}

@Suppress("unused")
val wazeFullBlackPatch: ResourcePatch = resourcePatch(
    name = "Full black",
    description = "Waze's dark mode in true black for AMOLED screens: the app's dark backgrounds are black, and " +
        "with Black night map the night map's background too. Roads, labels, buttons and text keep their colours.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeBlackNightMapPatch)

    // Written into Waze's own copy of its night skins as Waze starts; can be switched in Customization.
    val blackMap by booleanOption(
        key = "blackMap",
        default = true,
        title = "Black night map",
        description = "The night map's background black as well, from Waze's next start. It can be switched " +
            "later in Customization, at the bottom of Waze's side menu.",
    )

    execute {
        // The app: night colours that are backgrounds (by name) and dark become black.
        val colours = get("res/values-night/colors.xml")
        if (!colours.isFile) throw PatchException("res/values-night/colors.xml not found")
        val palette = Regex("<color name=\"([^\"]+)\">#([0-9a-fA-F]{6,8})</color>")
            .findAll(get("res/values/colors.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
        fun argb(value: String): Long? {
            val hex = when {
                value.startsWith("#") -> value.substring(1)
                value.startsWith("@color/") -> palette[value.removePrefix("@color/")] ?: return null
                else -> return null
            }
            return when (hex.length) {
                6 -> 0xff000000L or hex.toLong(16)
                8 -> hex.toLong(16)
                else -> null
            }
        }
        var blackened = 0
        val text = Regex("<color name=\"([^\"]+)\">([^<]+)</color>").replace(colours.readText()) { m ->
            val name = m.groupValues[1]
            val value = m.groupValues[2].trim()
            val background = name.contains("background", ignoreCase = true) || name.endsWith("Bg")
            val colour = argb(value)
            if (background && colour != null && darkNeutral(colour) && (colour and 0xffffffL) != 0L) {
                blackened++
                "<color name=\"$name\">#ff000000</color>"
            } else {
                m.value
            }
        }
        if (blackened == 0) throw PatchException("no dark night backgrounds found")
        colours.writeText(text)
    }
}
