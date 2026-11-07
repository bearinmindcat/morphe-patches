package org.ungoogled.patches.lggallery

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object LgGalleryConstants {
    /**
     * LG Gallery is a single APK (package com.android.gallery3d, as on LG's phones). Pinned to the version tested.
     */
    val COMPATIBILITY_LG_GALLERY = Compatibility(
        name = "LG Gallery",
        packageName = "com.android.gallery3d",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x941CF9,
        targets = listOf(
            AppTarget(version = "9.60.32", minSdk = 30, description = "needs Android 11 or newer"),
        ),
    )
}
