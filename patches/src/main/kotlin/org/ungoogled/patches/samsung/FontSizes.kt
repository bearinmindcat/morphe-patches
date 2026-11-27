package org.ungoogled.patches.samsung

import app.morphe.patcher.patch.ResourcePatchContext

/*
 * Samsung sets much of its apps' text in dp, which Android's font size leaves alone (One UI does too: the sizes are a
 * design choice). Text in sp follows the font size, as in other apps.
 */

/** A text size attribute or style item: android:textSize, app:tabTextSize, ... */
private val TEXT_SIZE_ATTRIBUTE = Regex("""(\b[\w:]*[tT]extSize=")([^"]+)(")""")
private val TEXT_SIZE_ITEM = Regex("""(<item name="[\w:]*[tT]extSize">)([^<]+)(</item>)""")
private val STYLE = Regex("""<style name="([^"]+)"[^>]*?(?:/>|>.*?</style>)""", RegexOption.DOT_MATCHES_ALL)
private val DIMEN = Regex("""(<dimen name="([^"]+)">)([^<]+)(</dimen>)""")
private val DP = Regex("""^(-?\d+(?:\.\d+)?)(?:dp|dip)$""")
private const val DIMEN_REFERENCE = "@dimen/"

/** Names that read as a text size; their margins, paddings, heights and the like are not. */
private val TEXT_NAME = Regex("""(^|_)(text|texts|font|numbering)(_|\d|$)|textsize|fontsize""", RegexOption.IGNORE_CASE)
private val NOT_TEXT_NAME = Regex(
    "margin|padding|pad_|_pad|spacing|height|width|radius|offset|gap|space|inset|elevation|icon|corner|stroke|divider|" +
        "baseline|translate|_min_|_max_|thickness|indicator|shadow|line_extra|bubble|area|interval|distance|touch|" +
        "depth|blur|_top$|_bottom$|_start$|_end$|_left$|_right$|_x$|_y$",
    RegexOption.IGNORE_CASE,
)

/**
 * Libraries' resources (Samsung's SESL, AppCompat, Material) keep their own sizes: their code reads them with its own
 * font-size rules. Home screen widgets are laid out by the launcher in fixed cells and stay as designed.
 */
private fun isLibraryOrWidget(name: String) =
    name.lowercase().let { lower ->
        listOf("sesl", "abc_", "mtrl_", "design_", "m3_", "material_", "exo_").any { lower.startsWith(it) } ||
            "sesl" in lower || "widget" in lower
    }

/** The default for [textSizesToSp]'s dimens read by code: names like "mini_player_artist_text" or "..._font_size". */
internal fun isTextSizeName(name: String) = TEXT_NAME.containsMatchIn(name) && !NOT_TEXT_NAME.containsMatchIn(name)

/** "17.0dp" -> "17.0sp"; anything else stays as it is. */
private fun toSp(value: String) = DP.matchEntire(value.trim())?.let { "${it.groupValues[1]}sp" }

/**
 * Turns the app's text sizes in dp into sp, so its text follows Android's font size: text size attributes in layouts
 * and style items given in dp, the dimens they name, and the dimens the code sets text sizes from ([codeTextSize], by
 * name). Libraries' and widgets' resources are left alone (see isLibraryOrWidget). Returns the values changed.
 */
internal fun ResourcePatchContext.textSizesToSp(codeTextSize: (String) -> Boolean = ::isTextSizeName): Int {
    // Patcher 2.0 decodes resources on request.
    val files = listDecodableFiles("res/**").filter { path ->
        val dir = path.split('/').getOrNull(1) ?: return@filter false
        path.endsWith(".xml") && path.count { it == '/' } == 2 &&
            (dir == "layout" || dir.startsWith("layout-") || dir == "values" || dir.startsWith("values-"))
    }
    val layouts = files.filter { it.split('/')[1].startsWith("layout") }
    val styles = files.filter { it.endsWith("/styles.xml") }
    val dimens = files.filter { it.endsWith("/dimens.xml") }
    decode(*(layouts + styles + dimens).toTypedArray())

    var changed = 0
    val named = mutableSetOf<String>() // dimens named as a text size
    fun textSize(value: String): String? {
        if (value.startsWith(DIMEN_REFERENCE)) {
            named += value.removePrefix(DIMEN_REFERENCE)
            return null
        }
        return toSp(value)
    }

    for (path in layouts) {
        if (isLibraryOrWidget(path.substringAfterLast('/'))) continue
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        val out = TEXT_SIZE_ATTRIBUTE.replace(text) { m ->
            textSize(m.groupValues[2])?.let { changed++; m.groupValues[1] + it + m.groupValues[3] } ?: m.value
        }
        if (out != text) file.writeText(out)
    }

    for (path in styles) {
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        val out = STYLE.replace(text) { style ->
            if (isLibraryOrWidget(style.groupValues[1])) return@replace style.value
            TEXT_SIZE_ITEM.replace(style.value) { m ->
                textSize(m.groupValues[2])?.let { changed++; m.groupValues[1] + it + m.groupValues[3] } ?: m.value
            }
        }
        if (out != text) file.writeText(out)
    }

    // Dimens: those named above, those the code sets text sizes from, and whatever they in turn name.
    val values = dimens.mapNotNull { path -> get(path).takeIf { it.isFile }?.let { path to it.readText() } }
    val all = values.flatMap { (_, text) -> DIMEN.findAll(text).map { it.groupValues[2] to it.groupValues[3].trim() } }
    val wanted = (named + all.map { it.first }.filter { codeTextSize(it) }).filterNot(::isLibraryOrWidget).toMutableSet()
    while (true) {
        val more = all.filter { (name, value) -> name in wanted && value.startsWith(DIMEN_REFERENCE) }
            .map { it.second.removePrefix(DIMEN_REFERENCE) }.filterNot { it in wanted || isLibraryOrWidget(it) }
        if (more.isEmpty()) break
        wanted += more
    }
    for ((path, text) in values) {
        val out = DIMEN.replace(text) { m ->
            if (m.groupValues[2] !in wanted) return@replace m.value
            toSp(m.groupValues[3])?.let { changed++; m.groupValues[1] + it + m.groupValues[4] } ?: m.value
        }
        if (out != text) get(path).writeText(out)
    }
    return changed
}
