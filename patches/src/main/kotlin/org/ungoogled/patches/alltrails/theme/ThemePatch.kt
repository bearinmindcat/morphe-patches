package org.ungoogled.patches.alltrails.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import org.ungoogled.patches.alltrails.profile.experimentalScreenPatch
import org.ungoogled.patches.alltrails.profile.markPatched
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_ALLTRAILS
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.w3c.dom.Element
import java.io.File

private const val THEME = "Lorg/ungoogled/alltrails/Theme;"
private const val OVERLAY = "MorpheAmoled"

/**
 * AMOLED black for view screens, applied by Theme over each activity's own theme:
 * every ?denaliColorBackground* a dark screen paints with, one neutral step
 * darker, the base pure black. Theme.AMOLED_PALETTE does the same to Compose.
 */
private val AMOLED_ATTRIBUTES = listOf(
    "denaliColorBackgroundPrimary" to "color_neutral_black",
    "denaliColorBackgroundPrimaryPress" to "color_neutral_1000",
    "denaliColorBackgroundSecondary" to "color_neutral_1000",
    "denaliColorBackgroundSecondaryPress" to "color_neutral_950",
    "denaliColorBackgroundElevated" to "color_neutral_1000",
    "denaliColorBackgroundTertiary" to "color_neutral_950",
    "denaliColorBackgroundTertiaryPress" to "color_neutral_900",
)

private val themeResourcePatch = resourcePatch(
    description = "Adds the AMOLED black theme overlay.",
) {
    execute {
        val colours = get("res/values/colors.xml").readText()
        val missing = AMOLED_ATTRIBUTES.map { it.second }.filterNot { colours.contains("<color name=\"$it\">") }
        if (missing.isNotEmpty()) throw PatchException("Denali neutrals missing: $missing")

        document("res/values/styles.xml").use { xml ->
            val style = xml.createElement("style")
            style.setAttribute("name", OVERLAY)
            for ((attribute, colour) in AMOLED_ATTRIBUTES) {
                val item = xml.createElement("item")
                item.setAttribute("name", attribute)
                item.textContent = "@color/$colour"
                style.appendChild(item)
            }
            xml.documentElement.appendChild(style)
        }

        // The one full-width strip that names its background colour instead of the attribute:
        // the tabs of a profile's Followers / Following lists.
        document("res/layout/follow_lists_fragment.xml").use { xml ->
            val all = xml.getElementsByTagName("*")
            val tabs = (0 until all.length).map { all.item(it) as Element }
                .filter { it.getAttribute("app:tabBackground") == "@color/color_background_primary" }
            if (tabs.size != 1) throw PatchException("follow lists' tab strip no longer paints color_background_primary")
            tabs.single().setAttribute("app:tabBackground", "?attr/denaliColorBackgroundPrimary")
        }
    }
}

/** DenaliColors' static initializer: it builds the unspecified, light and dark palette maps from these keys. */
private object DenaliColorsInitFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("colorBackgroundPrimary", "colorNeutralBlack", "colorNeutral1000", "colorNeutralWhite"),
)

/**
 * A Theme choice -- Follow phone, Light, Dark or AMOLED black -- on the
 * Experimental screen (see Theme in the extension):
 *
 * - Light and Dark are the app's own night mode, set by the extension.
 * - AMOLED black: the MorpheAmoled overlay (above) for view screens; for Compose,
 *   DenaliColors hands its palette maps (their backing LinkedHashMaps, which its
 *   DefaultMap wrapper reads through) to the extension at the end of its static
 *   initializer, and the extension re-points the dark map's background entries.
 */
@Suppress("unused")
val themePatch = bytecodePatch(
    name = "Theme",
    description = "Adds a Theme choice to the Experimental screen: Follow phone, Light, Dark or AMOLED black " +
        "(dark with pure black backgrounds). AllTrails itself only follows the phone.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALLTRAILS)
    dependsOn(experimentalScreenPatch, themeResourcePatch)

    execute {
        markPatched(THEME)

        DenaliColorsInitFingerprint.let { fp ->
            val colors = fp.classDef
            // The palette maps: DenaliColors' static fields of its map type (DefaultMap).
            val maps = colors.fields.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.type != colors.type && it.type.startsWith("L") }
            val mapType = maps.map { it.type }.distinct().singleOrNull()
            if (maps.size != 3 || mapType == null) throw PatchException("DenaliColors no longer holds three palette maps: ${maps.map { it.type }}")
            val backing = classDefBy(mapType).fields.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "Ljava/util/Map;" }
                ?: throw PatchException("$mapType no longer wraps one Map")

            val method = fp.method
            val end = method.implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
            // End of a static initializer: every register is free.
            method.addInstructionsAtLabel(
                end,
                maps.joinToString("\n") { field ->
                    """
                        sget-object v0, ${colors.type}->${field.name}:$mapType
                        iget-object v0, v0, $mapType->${backing.name}:Ljava/util/Map;
                        invoke-static { v0 }, $THEME->palette(Ljava/util/Map;)V
                    """.trimIndent()
                },
            )
        }
    }
}
