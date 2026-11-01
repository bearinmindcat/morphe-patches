package org.ungoogled.patches.honor

import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

/**
 * Android's plain activity themes (Holo, Material, DeviceDefault; light or dark; not dialogs, panels or translucent
 * ones), which MagicOS swaps for its own Theme.Magic when the activity asks for it.
 */
private val ANDROID_THEME = Regex("""^@android:style/Theme(\.(Holo|Material|DeviceDefault))?(\.Light)?(\.DarkActionBar|\.NoActionBar)?$""")

/** MagicOS's day/night themes an activity can ask for in its "hnc-theme" meta-data (dialogs and the like are left alone). */
private val MAGIC_THEME = Regex("""^androidhnext:style/Theme\.Magic(\.NoActionBar|\.WithActionBar|\.NoTitleBar|\.Translucent)?$""")

/** The meta-data naming that theme: "hnc-theme", or Huawei's older "hwc-theme" (Calendar, Files, Notes). */
private val MAGIC_THEME_KEYS = setOf("hnc-theme", "hwc-theme")

/** The app styles added: Android's own day/night theme, with status and navigation bar icons that follow it. */
internal const val MAGIC_DAY_NIGHT = "MagicDayNight"
internal const val MAGIC_DAY_NIGHT_NO_ACTION_BAR = "MagicDayNight.NoActionBar"
/** MagicOS's translucent day/night theme (sheets and overlays drawn over the previous screen). */
internal const val MAGIC_DAY_NIGHT_TRANSLUCENT = "MagicDayNight.Translucent"

private fun Element.children(tag: String) =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

/**
 * On MagicOS an activity whose meta-data names a Theme.Magic style ("hnc-theme") gets that theme, which follows the
 * phone's dark mode; the Android theme it declares (a light Material one, or even dark Holo) only matters elsewhere.
 * Off MagicOS that theme stays, so the app's own day or night colours sit under action bars, popup menus and bar icons
 * of the other mode. Re-bases each such activity on Android's day/night theme instead: an Android theme it names
 * directly becomes [MAGIC_DAY_NIGHT] (or its NoActionBar variant, as Theme.Magic.NoActionBar is), as does Android's
 * default when it names none, and an app style it names gets that parent where its own chain reaches an Android theme.
 * Returns the activities and styles changed.
 */
internal fun ResourcePatchContext.followMagicDayNight(): Pair<Int, Int> {
    // activity theme -> whether MagicOS would give it no action bar
    val wanted = mutableMapOf<String, Boolean>()
    var activities = 0
    // The translucent style is added only for an app that has such an activity.
    var translucentUsed = false
    document("AndroidManifest.xml").use { manifest ->
        val application = manifest.getElementsByTagName("application").item(0) as Element
        fun meta(element: Element): String? = element.children("meta-data")
            .firstOrNull { it.getAttribute("android:name") in MAGIC_THEME_KEYS }?.getAttribute("android:value")
        val appMagic = meta(application)?.takeIf { MAGIC_THEME.matches(it) }
        for (tag in listOf("activity", "activity-alias")) {
            val list = manifest.getElementsByTagName(tag)
            for (i in 0 until list.length) {
                val activity = list.item(i) as Element
                // An activity's own MagicOS theme wins over the application's, also when it is one left alone (dialogs,
                // translucent ones).
                val own = meta(activity)
                val hnc = (if (own != null) own.takeIf { MAGIC_THEME.matches(it) } else appMagic) ?: continue
                val theme = activity.getAttribute("android:theme").ifEmpty { application.getAttribute("android:theme") }
                val noActionBar = hnc.endsWith(".NoActionBar") || hnc.endsWith(".NoTitleBar")
                val translucent = hnc.endsWith(".Translucent")
                when {
                    // Translucent: only where the activity names no theme of its own (or a plain Android one).
                    translucent -> if (theme.isEmpty() || ANDROID_THEME.matches(theme)) {
                        activity.setAttribute("android:theme", "@style/$MAGIC_DAY_NIGHT_TRANSLUCENT")
                        activities++
                        translucentUsed = true
                    }
                    // No theme at all (Android's default, light with an action bar): as an Android theme named directly.
                    theme.isEmpty() || ANDROID_THEME.matches(theme) -> {
                        val bar = noActionBar || theme.endsWith(".NoActionBar")
                        activity.setAttribute("android:theme", "@style/" + if (bar) MAGIC_DAY_NIGHT_NO_ACTION_BAR else MAGIC_DAY_NIGHT)
                        activities++
                    }
                    theme.startsWith("@style/") -> wanted.merge(theme.removePrefix("@style/"), noActionBar, Boolean::or)
                }
            }
        }
    }
    var styles = 0
    for (path in listDecodableFiles("res/values*/styles.xml")) {
        document(path).use { xml ->
            val all = xml.documentElement.children("style")
            val byName = all.associateBy { it.getAttribute("name") }
            fun parentOf(style: Element): String {
                val parent = style.getAttribute("parent")
                if (parent.isNotEmpty()) return parent
                val name = style.getAttribute("name")
                return if (name.contains('.')) "@style/" + name.substringBeforeLast('.') else ""
            }
            for ((name, noActionBar) in wanted) {
                var style = byName[name]
                val seen = mutableSetOf<String>()
                while (style != null && seen.add(style.getAttribute("name"))) {
                    val parent = parentOf(style)
                    if (ANDROID_THEME.matches(parent)) {
                        val bar = noActionBar || parent.endsWith(".NoActionBar")
                        style.setAttribute("parent", "@style/" + if (bar) MAGIC_DAY_NIGHT_NO_ACTION_BAR else MAGIC_DAY_NIGHT)
                        styles++
                        break
                    }
                    style = if (parent.startsWith("@style/")) byName[parent.removePrefix("@style/")] else null
                }
            }
            if (path == "res/values/styles.xml") {
                fun add(name: String, parent: String, items: List<Pair<String, String>>) {
                    if (byName.containsKey(name)) return
                    val style = xml.createElement("style")
                    style.setAttribute("name", name)
                    style.setAttribute("parent", parent)
                    for ((key, value) in items) {
                        style.appendChild(xml.createElement("item").apply { setAttribute("name", key); textContent = value })
                    }
                    xml.documentElement.appendChild(style)
                }
                add(
                    MAGIC_DAY_NIGHT, "@android:style/Theme.DeviceDefault.DayNight",
                    listOf(
                        "android:windowLightStatusBar" to "?android:attr/isLightTheme",
                        "android:windowLightNavigationBar" to "?android:attr/isLightTheme",
                    ),
                )
                add(
                    MAGIC_DAY_NIGHT_NO_ACTION_BAR, "@style/$MAGIC_DAY_NIGHT",
                    listOf("android:windowActionBar" to "false", "android:windowNoTitle" to "true"),
                )
                if (translucentUsed) add(
                    // With an action bar, as MagicOS's (the app hides it itself).
                    MAGIC_DAY_NIGHT_TRANSLUCENT, "@style/$MAGIC_DAY_NIGHT",
                    listOf(
                        "android:windowIsTranslucent" to "true",
                        "android:windowBackground" to "@android:color/transparent",
                        "android:backgroundDimEnabled" to "false",
                    ),
                )
            }
        }
    }
    return activities to styles
}
