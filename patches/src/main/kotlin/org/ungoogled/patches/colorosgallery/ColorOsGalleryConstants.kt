package org.ungoogled.patches.colorosgallery

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsGalleryConstants {
    /**
     * OnePlus Photos is a single APK: 16.45.2 for Android 13 and newer, 15.33.40 for Android 11 and 12. Pinned to the
     * versions tested: R8 renames its classes every build. Newest first (the version check suggests the first one the
     * phone can run).
     */
    val COMPATIBILITY_COLOROS_GALLERY = Compatibility(
        name = "OnePlus Photos",
        packageName = "com.oneplus.gallery",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x2C67E9,
        targets = listOf(
            AppTarget(version = "16.45.2", minSdk = 33, description = "needs Android 13 or newer"),
            AppTarget(version = "15.33.40", minSdk = 30, description = "needs Android 11 or newer"),
        ),
    )
}
