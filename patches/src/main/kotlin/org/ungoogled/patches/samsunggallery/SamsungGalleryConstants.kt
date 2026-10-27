package org.ungoogled.patches.samsunggallery

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungGalleryConstants {
    /**
     * Samsung Gallery ships inside One UI (/system/priv-app/SamsungGallery2018) and updates from the Galaxy Store as
     * a single APK. Pinned to the version pulled from the user's Galaxy Z Flip7: R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_GALLERY = Compatibility(
        name = "Samsung Gallery",
        packageName = "com.sec.android.gallery3d",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF0466E,
        targets = listOf(
            AppTarget(version = "15.9.01.7"),
        ),
    )
}
