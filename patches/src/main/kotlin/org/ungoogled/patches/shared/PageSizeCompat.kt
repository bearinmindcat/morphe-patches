package org.ungoogled.patches.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

/**
 * Native libraries a vendor built with 4 KB ELF alignment do not load on a phone that runs 16 KB memory pages (Pixel 8
 * and newer with the 16 KB developer option, Android's canary images): dlopen fails with "program alignment (4096)
 * cannot be smaller than system page size (16384)" and the app dies. Android 16 and newer run such an app in their page
 * size compatibility mode when its manifest asks for it (android:pageSizeCompat="enabled"); 4 KB phones and older
 * Android ignore the attribute.
 */
internal fun ResourcePatchContext.enablePageSizeCompat() {
    document("AndroidManifest.xml").use { manifest ->
        val application = manifest.getElementsByTagName("application").item(0) as? Element
            ?: throw PatchException("no application element in the manifest")
        // By id ("r0x" + hex, the resource encoder's form for attributes its framework table lacks): the table it picks
        // follows the app's target SDK, and only Android 16's has pageSizeCompat. 0x20 = "enabled".
        application.setAttribute("android:r0x010106ab", "32")
    }
}
