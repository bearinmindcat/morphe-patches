package org.ungoogled.patches.maps.resources

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element
import java.io.File

private const val ICON = "@mipmap/maps_2025"
private const val ROUND_ICON = "@mipmap/maps_2025_round"
private const val LEGACY = "ungoogled_legacy_icon"

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
    description = "Uses the flat multicolour pin Maps had before the 2025 gradient icon as the launcher icon.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        val res = this["res"]
        File(res, "drawable/${LEGACY}_foreground.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <inset xmlns:android="http://schemas.android.com/apk/res/android"
                android:drawable="@drawable/product_logo_maps_color_192"
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
