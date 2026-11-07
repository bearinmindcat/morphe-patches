package org.ungoogled.patches.samsunglauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungLauncherConstants {
    /**
     * One UI Home is a single APK from the Galaxy Store. Pinned to the versions tested (R8 renames its classes every
     * build): 18.0.07.59, the newest that runs on Android 15, and 16.0.09.35, the newest for Android 13 and 14 (newest
     * first: the version check suggests the first one a phone can run).
     */
    val COMPATIBILITY_SAMSUNG_LAUNCHER = Compatibility(
        name = "One UI Home",
        packageName = "com.sec.android.app.launcher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3E91FF,
        targets = listOf(
            AppTarget(version = "18.0.07.59", minSdk = 35, description = "needs Android 15 or newer"),
            AppTarget(version = "16.0.09.35", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
