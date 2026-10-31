package org.ungoogled.patches.samsung

import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

/**
 * Off One UI the libraries Samsung's system provides are missing, and one the manifest requires stops the install
 * (INSTALL_FAILED_MISSING_SHARED_LIBRARY). Makes every uses-library and uses-native-library optional; the features
 * built on them stay off. Returns how many were required.
 */
internal fun ResourcePatchContext.makeLibrariesOptional(): Int {
    var required = 0
    document("AndroidManifest.xml").use { manifest ->
        for (tag in listOf("uses-library", "uses-native-library")) {
            val list = manifest.getElementsByTagName(tag)
            for (i in 0 until list.length) {
                val element = list.item(i) as Element
                if (element.getAttribute("android:required") != "false") required++
                element.setAttribute("android:required", "false")
            }
        }
    }
    return required
}
