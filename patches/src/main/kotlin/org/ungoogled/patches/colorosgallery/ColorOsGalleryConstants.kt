package org.ungoogled.patches.colorosgallery

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsGalleryConstants {
    /** OnePlus Photos is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_GALLERY = Compatibility(
        name = "OnePlus Photos",
        packageName = "com.oneplus.gallery",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x2C67E9,
        targets = listOf(
            AppTarget(version = "16.45.2"),
        ),
    )
}
