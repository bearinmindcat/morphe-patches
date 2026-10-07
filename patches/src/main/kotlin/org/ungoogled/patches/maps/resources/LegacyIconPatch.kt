package org.ungoogled.patches.maps.resources

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.maps.microg.MicrogSelection
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private const val ICON = "@mipmap/maps_2025"
private const val ROUND_ICON = "@mipmap/maps_2025_round"
private const val LEGACY = "ungoogled_legacy_icon"
private const val PIN = "@drawable/product_logo_maps_color_192"

/**
 * microG's C -- its own launcher logo, four arcs open to the right -- for microG Maps'
 * icon, so it tells itself apart from Ungoogled Maps. Drawn in the pin image's own space
 * (576 px at xxhdpi) on the pin's circle, centre (288, 208), radius 70, at 86% of it so a
 * white ring stays between the C and the blue; the ring keeps microG's proportions, 30
 * wide at radius 70.
 */
private fun microgC(): String {
    val cx = 288.0
    val cy = 208.0
    val outer = 70.0 * 0.86
    val width = outer * 30 / 70
    val r = outer - width / 2
    fun at(degrees: Int): String {
        val a = Math.toRadians(degrees.toDouble())
        return String.format(Locale.ROOT, "%.2f,%.2f", cx + r * cos(a), cy + r * sin(a))
    }
    val radius = String.format(Locale.ROOT, "%.2f,%.2f", r, r)
    val arcs = listOf(Triple(45, 120, "#E91E63"), Triple(120, 195, "#FF9800"), Triple(195, 240, "#CDDC39"), Triple(240, 315, "#009688"))
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        append("    android:width=\"192dp\" android:height=\"192dp\"\n")
        append("    android:viewportWidth=\"576\" android:viewportHeight=\"576\">\n")
        for ((from, to, colour) in arcs) {
            append("    <path android:strokeColor=\"$colour\" android:strokeLineCap=\"butt\"\n")
            append("        android:strokeWidth=\"${String.format(Locale.ROOT, "%.2f", width)}\"\n")
            append("        android:pathData=\"M${at(from)} A$radius 0 0,1 ${at(to)}\" />\n")
        }
        append("</vector>\n")
    }
}

/**
 * The launcher icon Maps used before the 2025 gradient one: the flat
 * multicolour pin on white.
 *
 * Built entirely from what this Maps version already ships, so no Google
 * artwork travels with the patch and no second APK is needed: the flat pin
 * itself is still in the app as drawable/product_logo_maps_color_192 (the
 * in-app logo), and the old icon's white background layer is the one the
 * current icon still uses. Only the placement had to be recovered: measured
 * off Maps 25.38's own adaptive foreground, the pin is centred and 48.15% of
 * the 108dp canvas tall. The in-app logo's pin fills 91.67% of its image, so
 * inset by 23.74% on every side it lands exactly there.
 *
 * Because the pin is a reference rather than a copy, Blue pin's recolouring
 * carries over to the launcher icon automatically. Themed-icon launchers get
 * Maps' current monochrome pin, the same silhouette.
 */
@Suppress("unused")
val legacyIconPatch = resourcePatch(
    name = "Legacy icon",
    description = "Uses the flat multicolour pin Maps had before the 2025 gradient icon as the launcher icon. " +
        "With Add microG support, microG's C sits in the pin's circle.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        val res = this["res"]
        // microG Maps: the same pin with microG's C laid over its circle.
        val pin = if (MicrogSelection.builds(this, "Legacy icon")) {
            File(res, "drawable/${LEGACY}_microg_c.xml").writeText(microgC())
            File(res, "drawable/${LEGACY}_microg_pin.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <layer-list xmlns:android="http://schemas.android.com/apk/res/android">
                    <item android:drawable="$PIN" />
                    <item android:drawable="@drawable/${LEGACY}_microg_c" />
                </layer-list>
                """.trimIndent() + "\n",
            )
            "@drawable/${LEGACY}_microg_pin"
        } else {
            PIN
        }
        File(res, "drawable/${LEGACY}_foreground.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <inset xmlns:android="http://schemas.android.com/apk/res/android"
                android:drawable="$pin"
                android:inset="23.74%" />
            """.trimIndent() + "\n",
        )
        File(res, "mipmap-anydpi").mkdirs()
        File(res, "mipmap-anydpi/$LEGACY.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@mipmap/adaptiveproduct_maps_background_color_108" />
                <foreground android:drawable="@drawable/${LEGACY}_foreground" />
                <monochrome android:drawable="@mipmap/maps_2025_monochrome_foreground" />
            </adaptive-icon>
            """.trimIndent() + "\n",
        )

        var icons = 0
        var roundIcons = 0
        document("AndroidManifest.xml").use { manifest ->
            val elements = manifest.getElementsByTagName("*")
            for (i in 0 until elements.length) {
                val element = elements.item(i) as Element
                if (element.getAttribute("android:icon") == ICON) {
                    element.setAttribute("android:icon", "@mipmap/$LEGACY"); icons++
                }
                if (element.getAttribute("android:roundIcon") == ROUND_ICON) {
                    element.setAttribute("android:roundIcon", "@mipmap/$LEGACY"); roundIcons++
                }
            }
        }
        if (icons == 0 || roundIcons == 0) throw PatchException("launcher icon attributes not found ($icons icon, $roundIcons roundIcon)")
    }
}
