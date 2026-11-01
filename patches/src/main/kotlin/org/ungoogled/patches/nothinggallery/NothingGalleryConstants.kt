package org.ungoogled.patches.nothinggallery

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object NothingGalleryConstants {
    /**
     * Nothing Gallery ships from Play as a split bundle (base + arm64 + language + density splits). Pinned to the
     * version tested: it is R8-obfuscated, so its class names change every build.
     */
    val COMPATIBILITY_NOTHING_GALLERY = Compatibility(
        name = "Nothing Gallery",
        packageName = "com.nothing.gallery",
        appIconColor = 0xD71921,
        targets = listOf(
            AppTarget(version = "3.1.3.1019"),
        ),
    )
}
