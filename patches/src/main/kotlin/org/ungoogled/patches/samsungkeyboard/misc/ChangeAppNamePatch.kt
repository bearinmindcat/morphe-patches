package org.ungoogled.patches.samsungkeyboard.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.w3c.dom.Element

/**
 * The keyboard lists, the input method picker and App info show the application's label, @string/app_name
 * ("Samsung Keyboard", translated in every language). Installed beside the stock keyboard, both would read the
 * same; as in Waze's Change app name, every such label is repointed at a NEW string without translations. In-app
 * copy that says "Samsung Keyboard" is untouched.
 */
private const val STOCK_LABEL = "@string/app_name"
private const val NEW_LABEL = "@string/patched_keyboard_name"

@Suppress("unused")
val samsungKeyboardChangeAppNamePatch = resourcePatch(
    name = "Change app name",
    description = "Renames Samsung Keyboard, so the two keyboards can be told apart.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    val appName = stringOption(
        key = "appName",
        default = "Private Keyboard",
        title = "App name",
        description = "Shown in the keyboard list and App info.",
        required = true,
    )

    execute {
        var relabeled = 0
        document("AndroidManifest.xml").use { manifest ->
            val elements = manifest.getElementsByTagName("*")
            for (i in 0 until elements.length) {
                val element = elements.item(i) as Element
                if (element.getAttribute("android:label") == STOCK_LABEL) {
                    element.setAttribute("android:label", NEW_LABEL)
                    relabeled++
                }
            }
        }
        if (relabeled == 0) throw PatchException("no manifest labels reference $STOCK_LABEL")

        document("res/values/strings.xml").use { strings ->
            val resources = strings.getElementsByTagName("resources").item(0) as Element
            resources.appendChild(
                strings.createElement("string").apply {
                    setAttribute("name", "patched_keyboard_name")
                    textContent = appName.value!!
                },
            )
        }
    }
}
