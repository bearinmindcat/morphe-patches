package org.ungoogled.patches.pdfcraft

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.pdfcraft.PdfCraftConstants.COMPATIBILITY_PDFCRAFT
import org.w3c.dom.Element

private const val STOCK_LABEL = "@string/app_name"
private const val NEW_STRING = "pdfcraft_app_name"

/** A name as an Android string resource: backslashes, quotes and apostrophes escaped, a leading @ or ? too. */
private fun resourceText(name: String): String {
    val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
    return if (escaped.startsWith("@") || escaped.startsWith("?")) "\\$escaped" else escaped
}

/**
 * The launcher name. PdfCraft's labels point at @string/app_name ("PdfCraft", translated into every language), so
 * each is repointed at a new string without translations and every language shows the chosen name. The package name
 * stays the same.
 */
@Suppress("unused")
val pdfCraftChangeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Renames PdfCraft on the home screen.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_PDFCRAFT)

    val appName = stringOption(
        key = "appName",
        default = "PDF Reader",
        title = "App name",
        description = "Shown on the home screen and in App info.",
        required = true,
    ) { !it.isNullOrBlank() }

    execute {
        var relabeled = 0
        document("AndroidManifest.xml").use { manifest ->
            val elements = manifest.getElementsByTagName("*")
            for (i in 0 until elements.length) {
                val element = elements.item(i) as Element
                if (element.getAttribute("android:label") == STOCK_LABEL) {
                    element.setAttribute("android:label", "@string/$NEW_STRING")
                    relabeled++
                }
            }
        }
        if (relabeled == 0) throw PatchException("no manifest labels reference $STOCK_LABEL")

        document("res/values/strings.xml").use { strings ->
            strings.documentElement.appendChild(
                strings.createElement("string").apply {
                    setAttribute("name", NEW_STRING)
                    textContent = resourceText(appName.value!!.trim())
                },
            )
        }
    }
}
