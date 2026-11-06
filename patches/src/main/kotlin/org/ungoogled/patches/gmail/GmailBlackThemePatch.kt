package org.ungoogled.patches.gmail

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL
import org.w3c.dom.Element

/**
 * Gmail's dark theme, as Material 3 colour roles: the ground (background, surface and the inbox's surface
 * container) goes pure black, and the raised levels keep a small step above it, so the search bar, sheets and
 * menus stay apart from the ground -- the same idea as Maps' Black theme, which lifts its containers off black.
 */
private val BLACK = linkedMapOf(
    "background" to "000000",
    "surface" to "000000",
    "surface_dim" to "000000",
    "surface_container_lowest" to "000000",
    "surface_container_low" to "000000",
    // The inbox's ground: black too, so its rows (surface) sit on black.
    "surface_container" to "000000",
    "surface_container_high" to "1a1a1a",
    "surface_container_highest" to "222222",
    "surface_bright" to "2a2a2a",
)

/** The colour families Gmail's dark screens draw with: Google Material 3 (static and wallpaper colours), Material 3, Google Material 2. */
private val FAMILIES = listOf("gm3_sys_color_dark_", "gm3_sys_color_dynamic_dark_", "m3_sys_color_dark_", "m3_sys_color_dynamic_dark_", "gm_sys_color_dark_")

/** Older surfaces: Material 2's dark defaults and the account setup screens (setup wizard). */
private val OTHERS = mapOf(
    "design_dark_default_color_background" to "000000",
    "design_dark_default_color_surface" to "000000",
    "gm3_legacy_sys_color_dark_background" to "000000",
    "sud_system_background_dark" to "000000",
    "sud_system_surface_dark" to "000000",
    "sud_system_surface_dim_dark" to "000000",
    "sud_system_surface_container_lowest_dark" to "000000",
)

/** Older Gmail screens (welcome tour, legacy lists) paint with Gmail's own palette, whose night "white" is grey 900. */
private val NIGHT_ONLY = mapOf(
    "ag_white" to "000000",
    "material_grey_white_1000" to "000000",
    "survey_surface_color" to "000000",
    "tasks_taskBackground" to "000000",
)

private fun black(name: String, night: Boolean): String? {
    if (night) NIGHT_ONLY[name]?.let { return it }
    OTHERS[name]?.let { return it }
    for (family in FAMILIES) {
        if (name.startsWith(family)) return BLACK[name.removePrefix(family)]
    }
    return null
}

/** Message bodies and the compose editor are web views styled by these sheets, each with its own dark block. */
private val STYLESHEETS = listOf("assets/conv.css", "assets/compose.css")
private const val DARK_BLOCK = "@media (prefers-color-scheme:dark){:root{"

@Suppress("unused")
val gmailBlackThemePatch = resourcePatch(
    name = "Black theme",
    description = "Pure black dark mode.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GMAIL)

    execute {
        // Patcher 2.0 decodes resources on request.
        decode("res/values*/colors.xml")
        var colours = 0
        val res = this["res"]
        for (dir in res.listFiles()!!.filter { it.isDirectory && it.name.startsWith("values") }) {
            if (!java.io.File(dir, "colors.xml").isFile) continue
            document("res/${dir.name}/colors.xml").use { xml ->
                val list = xml.getElementsByTagName("color")
                for (i in 0 until list.length) {
                    val colour = list.item(i) as Element
                    val value = black(colour.getAttribute("name"), "night" in dir.name) ?: continue
                    colour.textContent = "#ff$value"
                    colours++
                }
            }
        }

        var sheets = 0
        for (path in STYLESHEETS) {
            val file = get(path)
            if (!file.exists()) throw PatchException("$path not found")
            val css = file.readText()
            val start = css.indexOf(DARK_BLOCK)
            if (start < 0) throw PatchException("$path has no dark block")
            val end = css.indexOf('}', start + DARK_BLOCK.length)
            var block = css.substring(start, end)
            for ((role, value) in BLACK) {
                block = block.replace(Regex("(--md-sys-color-${role.replace('_', '-')}:)#[0-9a-fA-F]+(?=[;}]|$)"), "$1#$value")
            }
            if (block == css.substring(start, end)) throw PatchException("$path's dark block has no surface colours")
            file.writeText(css.substring(0, start) + block + css.substring(end))
            sheets++
        }

        // Measured against this Gmail version: 62 colour definitions across values, -v31 and -v34.
        if (colours < 50 || sheets != STYLESHEETS.size) throw PatchException("only $colours dark colours and $sheets style sheets to blacken")
    }
}
