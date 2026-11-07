package org.ungoogled.patches.shared

import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

/**
 * Android 12's system palette (the public colors `@android:color/system_*` that Android 11 and older lack), as Android
 * 15's framework sets them without wallpaper colors.
 */
private val SYSTEM_PALETTE = mapOf(
    "accent1_0" to "ffffffff", "accent1_10" to "fffefbff", "accent1_50" to "ffeef0ff", "accent1_100" to "ffd9e2ff",
    "accent1_200" to "ffb0c6ff", "accent1_300" to "ff94aae4", "accent1_400" to "ff7a90c8", "accent1_500" to "ff6076ac",
    "accent1_600" to "ff475d92", "accent1_700" to "ff2f4578", "accent1_800" to "ff152e60", "accent1_900" to "ff001945",
    "accent1_1000" to "ff000000",
    "accent2_0" to "ffffffff", "accent2_10" to "fffefbff", "accent2_50" to "ffeef0ff", "accent2_100" to "ffdce2f9",
    "accent2_200" to "ffc0c6dc", "accent2_300" to "ffa4abc1", "accent2_400" to "ff8a90a5", "accent2_500" to "ff70778b",
    "accent2_600" to "ff575e71", "accent2_700" to "ff404659", "accent2_800" to "ff2a3042", "accent2_900" to "ff151b2c",
    "accent2_1000" to "ff000000",
    "accent3_0" to "ffffffff", "accent3_10" to "fffffbff", "accent3_50" to "ffffebfa", "accent3_100" to "fffdd7fa",
    "accent3_200" to "ffe0bbdd", "accent3_300" to "ffc3a0c1", "accent3_400" to "ffa886a6", "accent3_500" to "ff8c6d8c",
    "accent3_600" to "ff725572", "accent3_700" to "ff593d59", "accent3_800" to "ff412742", "accent3_900" to "ff2a122c",
    "accent3_1000" to "ff000000",
    "neutral1_0" to "ffffffff", "neutral1_10" to "fffefbff", "neutral1_50" to "fff1f0f7", "neutral1_100" to "ffe2e2e9",
    "neutral1_200" to "ffc6c6cd", "neutral1_300" to "ffababb1", "neutral1_400" to "ff909097", "neutral1_500" to "ff76777d",
    "neutral1_600" to "ff5d5e64", "neutral1_700" to "ff45464c", "neutral1_800" to "ff2f3036", "neutral1_900" to "ff1a1b20",
    "neutral1_1000" to "ff000000",
    "neutral2_0" to "ffffffff", "neutral2_10" to "fffefbff", "neutral2_50" to "fff0f0fa", "neutral2_100" to "ffe1e2ec",
    "neutral2_200" to "ffc5c6d0", "neutral2_300" to "ffa9abb4", "neutral2_400" to "ff8f9099", "neutral2_500" to "ff757780",
    "neutral2_600" to "ff5c5e67", "neutral2_700" to "ff44464f", "neutral2_800" to "ff2e3038", "neutral2_900" to "ff191b23",
    "neutral2_1000" to "ff000000",
)

private val SYSTEM_COLOR = Regex("""@android:color/system_(\w+)""")

/** Android 12's palette colors in a resource value become their default ARGB. */
private fun withPalette(text: String) = SYSTEM_COLOR.replace(text) { match ->
    SYSTEM_PALETTE[match.groupValues[1]]?.let { "#$it" } ?: match.value
}

/** The value types whose files are looked through (strings and the like always have a default). */
private val VALUE_FILES = setOf("bools.xml", "colors.xml", "dimens.xml", "integers.xml", "styles.xml")

private val RESOURCE_PATH = Regex("""res/([a-z]+)(-[^/]+)?/([^/]+\.xml)""")
private val VERSION = Regex("""-v(\d+)$""")

private class Located(val type: String, val qualifiers: String, val version: Int?, val file: String) {
    /** The directory with the same qualifiers and no version. */
    val unversioned get() = "res/$type$qualifiers"
}

private fun locate(path: String): Located? {
    val match = RESOURCE_PATH.matchEntire(path) ?: return null
    val all = match.groupValues[2]
    val version = VERSION.find(all)?.groupValues?.get(1)?.toInt()
    return Located(match.groupValues[1], if (version != null) all.removeSuffix("-v$version") else all, version, match.groupValues[3])
}

/**
 * An app built for newer Android but declaring an older minimum (OnePlus System Launcher 15.6.24: Android 11) can carry
 * resources defined only for Android 12 or newer (values-v31, color-v31, anim-v33, ...): on the older Android they are
 * missing (Resources.NotFoundException), and so is Android 12's system palette they name. Each such resource gets its
 * oldest variant as the default, its system palette colors as plain colors; a color whose default is a theme attribute
 * (which Android 11 cannot read as a color) takes its newer variant's value too. Returns the resources given defaults.
 */
internal fun ResourcePatchContext.addDefaultsForOlderAndroid(newerThan: Int = 30): Int {
    val all = listDecodableFiles("res/**").mapNotNull { path -> locate(path)?.let { path to it } }
    val newer = { it: Located -> it.version != null && it.version > newerThan }
    var added = 0

    // Files (color state lists, drawables, animations): a name only newer directories have.
    val files = all.filter { (_, at) -> at.type != "values" }
    val older = files.filter { (_, at) -> !newer(at) }.map { (_, at) -> at.type to at.file }.toSet()
    files.filter { (_, at) -> newer(at) && (at.type to at.file) !in older }
        .groupBy { (_, at) -> Triple(at.type, at.qualifiers, at.file) }
        .forEach { (_, variants) ->
            val (path, at) = variants.minBy { (_, at) -> at.version!! }
            decode(path)
            val target = get("${at.unversioned}/${at.file}")
            target.parentFile.mkdirs()
            target.writeText(withPalette(get(path).readText()))
            added++
        }

    // Values: entries only newer directories have, and colors whose default is a theme attribute.
    val values = all.filter { (_, at) -> at.type == "values" && at.file in VALUE_FILES }
    decode(*values.map { it.first }.toTypedArray())
    fun entries(path: String): List<Element> {
        val file = get(path)
        if (!file.isFile) return emptyList()
        return document(path).use { xml ->
            val nodes = xml.documentElement.childNodes
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.hasAttribute("name") }
        }
    }
    val olderNames = values.filter { (_, at) -> !newer(at) }
        .flatMap { (path, at) -> entries(path).map { at.file to it.getAttribute("name") } }.toSet()
    // The oldest newer variant of each entry, per qualifiers (values-night-v31 -> values-night).
    val wanted = mutableMapOf<Triple<String, String, String>, Pair<Int, Element>>()
    for ((path, at) in values.filter { (_, at) -> newer(at) }) {
        for (element in entries(path)) {
            val key = Triple(at.unversioned, at.file, element.getAttribute("name"))
            val known = wanted[key]
            if (known == null || at.version!! < known.first) wanted[key] = at.version!! to element
        }
    }
    for ((target, group) in wanted.entries.groupBy { "${it.key.first}/${it.key.second}" }) {
        val file = get(target)
        if (!file.isFile) {
            file.parentFile.mkdirs()
            file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
        }
        document(target).use { xml ->
            val root = xml.documentElement
            val existing = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
                .filter { it.hasAttribute("name") }.associateBy { it.getAttribute("name") }
            for ((key, newest) in group) {
                val (_, fileName, name) = key
                val source = newest.second
                val present = existing[name]
                if (present != null) {
                    // Only a color that is a theme attribute here is replaced by its newer value.
                    if (fileName != "colors.xml" || !present.textContent.trim().startsWith("?")) continue
                    present.textContent = withPalette(source.textContent)
                    added++
                    continue
                }
                if ((fileName to name) in olderNames) continue
                val copy = xml.importNode(source, true) as Element
                fun recolor(element: Element) {
                    val children = element.childNodes
                    if ((0 until children.length).none { children.item(it) is Element }) {
                        element.textContent = withPalette(element.textContent)
                    } else {
                        (0 until children.length).mapNotNull { children.item(it) as? Element }.forEach(::recolor)
                    }
                }
                recolor(copy)
                root.appendChild(copy)
                added++
            }
        }
    }
    return added
}
