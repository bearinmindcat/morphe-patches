package org.ungoogled.patches.sony

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext

private const val PREFIX = "ungoogled_fw_"

/**
 * Colors of Android 15's private theme attributes, as Theme.DeviceDefault (night) and Theme.DeviceDefault.Light set
 * them: (light, dark) public system colors. Material 3 roles (materialColor*) follow one rule (see [materialColor]).
 */
private val OLDER_ATTRIBUTES = mapOf(
    "colorAccentPrimary" to ("system_accent1_100" to "system_accent1_100"),
    "colorAccentPrimaryVariant" to ("system_accent1_600" to "system_accent1_300"),
    "colorAccentSecondary" to ("system_accent2_100" to "system_accent2_100"),
    "colorAccentSecondaryVariant" to ("system_accent2_600" to "system_accent2_300"),
    "colorAccentTertiary" to ("system_accent3_100" to "system_accent3_100"),
    "colorAccentTertiaryVariant" to ("system_accent3_600" to "system_accent3_300"),
    "colorSurface" to ("system_neutral1_10" to "system_neutral1_800"),
    "colorSurfaceHeader" to ("system_neutral1_100" to "system_neutral1_700"),
    "colorSurfaceHighlight" to ("system_neutral1_0" to "system_neutral1_600"),
    "colorSurfaceVariant" to ("system_neutral2_100" to "system_neutral1_700"),
    "textColorOnAccent" to ("system_neutral1_900" to "system_neutral1_900"),
)

/**
 * materialColorX: system_x_light / system_x_dark, or system_x for the fixed roles (the same in both); the inverse roles
 * (materialColorOnSurfaceInverse, PrimaryInverse, SurfaceInverse) take the other theme's color.
 */
private fun materialColor(role: String): Pair<String, String> {
    val inverse = role.endsWith("Inverse")
    val base = role.removeSuffix("Inverse").replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    if ("fixed" in base) return "system_$base" to "system_$base"
    val light = "system_${base}_light"
    val dark = "system_${base}_dark"
    return if (inverse) dark to light else light to dark
}

/**
 * Android 15's private theme attributes (Material 3 roles and older accent/surface colors) that Xperia Home's layouts,
 * drawables and styles name. Android 16 dropped them and gave their ids to other attributes, so the launcher stopped
 * at its first layout ("Failed to resolve attribute"). Each becomes a color of the app's own (day and night values from
 * Android's public system colors, as Android 15's DeviceDefault themes set them), on every Android version alike.
 * Returns the references changed.
 */
internal fun ResourcePatchContext.replacePrivateThemeColors(): Int {
    val reference = Regex("""\?android:\^attr-private/(\w+)""")
    // Only where the references can be: decoding other resources (anim, xml) made Morphe add entries of their types.
    val where = Regex("""res/((layout|drawable|color)[^/]*/[^/]+|values[^/]*/(styles|colors)\.xml)""")
    val files = listDecodableFiles("res/**").filter { where.matches(it) && it.endsWith(".xml") }
    decode(*files.toTypedArray())
    val used = sortedSetOf<String>()
    var changed = 0
    for (path in files) {
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        if (!reference.containsMatchIn(text)) continue
        file.writeText(
            reference.replace(text) { match ->
                val name = match.groupValues[1]
                if (name !in OLDER_ATTRIBUTES && !name.startsWith("materialColor")) {
                    throw PatchException("$path: private theme attribute $name has no color known")
                }
                used += name
                changed++
                "@color/$PREFIX$name"
            },
        )
    }
    if (used.isEmpty()) return 0
    // New entries get their ids after evenResourceEntryCounts counted the table (from public.xml): an even number of
    // them keeps each type's count even (an unused pad color where needed).
    val entries = used.toMutableList<String>()
    if (entries.size % 2 == 1) entries += "pad"

    fun colors(path: String, night: Boolean) {
        val file = get(path)
        if (!file.isFile) {
            file.parentFile.mkdirs()
            file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
        }
        document(path).use { xml ->
            for (name in entries) {
                val (light, dark) = if (name == "pad") "transparent" to "transparent"
                    else OLDER_ATTRIBUTES[name] ?: materialColor(name.removePrefix("materialColor"))
                xml.documentElement.appendChild(
                    xml.createElement("color").apply {
                        setAttribute("name", "$PREFIX$name")
                        textContent = "@android:color/${if (night) dark else light}"
                    },
                )
            }
        }
    }
    decode("res/values/colors.xml", "res/values-night/colors.xml")
    colors("res/values/colors.xml", night = false)
    colors("res/values-night/colors.xml", night = true)
    return changed
}
