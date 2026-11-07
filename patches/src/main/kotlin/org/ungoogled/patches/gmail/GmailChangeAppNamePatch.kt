package org.ungoogled.patches.gmail

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL
import org.w3c.dom.Element

private const val STOCK_LABEL = "@string/ag_app_name"
/** The same string Add microG-RE support points the labels at, so the two patches agree in either order. */
private const val NEW_STRING = "ungoogled_app_name"

/** A name as an Android string resource: backslashes, quotes and apostrophes escaped, a leading @ or ? too. */
private fun resourceText(name: String): String {
    val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
    return if (escaped.startsWith("@") || escaped.startsWith("?")) "\\$escaped" else escaped
}

/**
 * The launcher and app-switcher name. Gmail's labels point at @string/ag_app_name, which the language splits
 * translate, so each label is repointed at a string only the base defines (Add microG-RE support's, which this
 * patch's name then replaces). In-app copy that says "Gmail" is untouched.
 */
@Suppress("unused")
val gmailChangeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Renames Gmail on the home screen.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GMAIL)

    val appName = stringOption(
        key = "appName",
        default = "Mail",
        title = "App name",
        description = "Shown on the home screen and in the app switcher.",
        required = true,
    ) { !it.isNullOrBlank() }

    execute {
        var relabeled = 0
        document("AndroidManifest.xml").use { manifest ->
            val elements = manifest.getElementsByTagName("*")
            for (i in 0 until elements.length) {
                val element = elements.item(i) as Element
                // @string/ungoogled_app_name already: Add microG-RE support ran first.
                if (element.getAttribute("android:label") in setOf(STOCK_LABEL, "@string/$NEW_STRING")) {
                    element.setAttribute("android:label", "@string/$NEW_STRING")
                    relabeled++
                }
            }
        }
        if (relabeled == 0) throw PatchException("no manifest labels reference $STOCK_LABEL")

        document("res/values/strings.xml").use { strings ->
            val list = strings.getElementsByTagName("string")
            val existing = (0 until list.length).map { list.item(it) as Element }.firstOrNull { it.getAttribute("name") == NEW_STRING }
            (existing ?: strings.createElement("string").also {
                it.setAttribute("name", NEW_STRING)
                strings.documentElement.appendChild(it)
            }).textContent = resourceText(appName.value!!.trim())
        }
    }
}
