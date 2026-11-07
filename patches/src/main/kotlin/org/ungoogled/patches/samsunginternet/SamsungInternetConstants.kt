package org.ungoogled.patches.samsunginternet

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungInternetConstants {
    /**
     * Samsung Internet (labelled "Samsung Browser" since 2025) is one APK for every phone (arm64-v8a, the armeabi-v7a
     * half has no Chromium engine), signed by Samsung, minSdk 30. Its own code keeps readable names; pinned to the
     * build the patches were made against.
     */
    val COMPATIBILITY_SAMSUNG_INTERNET = Compatibility(
        name = "Samsung Internet",
        packageName = "com.sec.android.app.sbrowser",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x4049EA,
        targets = listOf(
            AppTarget(version = "31.0.0.79", minSdk = 30, description = "needs Android 11 or newer"),
        ),
    )
}

internal const val SBROWSER = "Lcom/sec/android/app/sbrowser/"
