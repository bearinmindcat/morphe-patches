package org.ungoogled.patches.waze

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE
import org.w3c.dom.Element

/**
 * Waze's launcher, app-switcher and Android Auto labels all point at @string/app_name_regular ("Waze"), or at
 * @string/app_name_beta ("Waze β") in Waze's beta builds (5.26.90.901 is one).
 * As in Maps' Change app name, every such label is repointed at a NEW string only the base module
 * defines, so no language split can shadow it. In-app copy that says "Waze" is untouched.
 */
private val STOCK_LABELS = setOf("@string/app_name_regular", "@string/app_name_beta")
private const val NEW_LABEL = "@string/dewaze_app_name"
private const val DEWAZE_NAME = "De-Waze"

@Suppress("unused")
val wazeChangeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Renames Waze.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_WAZE)

    val appName = stringOption(
        key = "appName",
        default = DEWAZE_NAME,
        title = "App name",
        description = "Shown on the home screen and Android Auto.",
        required = true,
    )

    execute {
        var relabeled = 0
        document("AndroidManifest.xml").use { manifest ->
            val elements = manifest.getElementsByTagName("*")
            for (i in 0 until elements.length) {
                val element = elements.item(i) as Element
                if (element.getAttribute("android:label") in STOCK_LABELS) {
                    element.setAttribute("android:label", NEW_LABEL)
                    relabeled++
                }
            }
        }
        if (relabeled == 0) throw PatchException("no manifest labels reference ${STOCK_LABELS.joinToString(" or ")}")

        document("res/values/strings.xml").use { strings ->
            val resources = strings.getElementsByTagName("resources").item(0) as Element
            resources.appendChild(
                strings.createElement("string").apply {
                    setAttribute("name", "dewaze_app_name")
                    textContent = appName.value!!
                },
            )
        }
    }
}
