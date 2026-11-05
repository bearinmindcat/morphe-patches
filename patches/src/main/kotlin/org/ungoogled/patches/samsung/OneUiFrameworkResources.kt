package org.ungoogled.patches.samsung

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

/**
 * Resources only One UI's framework has (package "android") that Samsung apps look up by name and use unchecked, as
 * "type/name" to their value: off One UI the lookup answers 0, and using 0 throws (Clock in dark mode: getColor(0)
 * on its alarm and world clock lists, Resources$NotFoundException). Each value is One UI's default theme's, which the
 * apps' own night resources repeat (tw_screen_background_color_dark = their dark content area colour).
 */
internal val ONE_UI_FRAMEWORK_RESOURCES = mapOf(
    "color/tw_screen_background_color_dark" to "#ff17171a",
)

/** The app resource standing in for One UI's framework resource [name]; the runtime looks it up by this name. */
private fun standIn(name: String) = "oneui_$name"

private fun Element.children(tag: String) =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

/**
 * Adds the [ONE_UI_FRAMEWORK_RESOURCES] to the app as "oneui_<name>" (values/<type>s.xml), where the runtime's
 * getIdentifier finds them (see [redirectOneUiFrameworkLookups]). Returns how many it added.
 */
internal fun ResourcePatchContext.addOneUiFrameworkResources(): Int {
    val byType = ONE_UI_FRAMEWORK_RESOURCES.entries.groupBy({ it.key.substringBefore('/') }) {
        it.key.substringAfter('/') to it.value
    }
    // Patcher 2.0 decodes resources on request.
    decode(*byType.keys.map { "res/values/${it}s.xml" }.toTypedArray())
    var added = 0
    for ((type, values) in byType) {
        val path = "res/values/${type}s.xml"
        val file = get(path)
        if (!file.isFile) {
            file.parentFile.mkdirs()
            file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
        }
        document(path).use { xml ->
            val have = xml.documentElement.children(type).map { it.getAttribute("name") }.toSet()
            for ((name, value) in values) {
                if (standIn(name) in have) continue
                xml.documentElement.appendChild(xml.createElement(type).apply {
                    setAttribute("name", standIn(name))
                    textContent = value
                })
                added++
            }
        }
    }
    return added
}

/**
 * Sends the app's Resources.getIdentifier calls through [runtime]'s static getIdentifier(Resources, String, String,
 * String), which answers a lookup of a One UI framework resource the phone lacks with the app's "oneui_<name>" stand-in
 * (added by [addOneUiFrameworkResources]); every other lookup answers as Android does. Returns the calls moved.
 */
internal fun BytecodePatchContext.redirectOneUiFrameworkLookups(runtime: String): Int = redirectCalls(
    runtime,
    mapOf("Landroid/content/res/Resources;" to setOf("getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I")),
)
