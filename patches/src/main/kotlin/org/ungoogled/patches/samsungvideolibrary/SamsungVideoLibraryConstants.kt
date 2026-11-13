package org.ungoogled.patches.samsungvideolibrary

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungVideoLibraryConstants {
    /**
     * Video Library is a single APK. Pinned to 1.4.22.81, Samsung's last build (2023; the app was discontinued on
     * January 1, 2024): R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_VIDEO_LIBRARY = Compatibility(
        name = "Samsung Video Library",
        packageName = "com.samsung.android.videolist",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x6E269D,
        targets = listOf(
            AppTarget(version = "1.4.22.81", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
