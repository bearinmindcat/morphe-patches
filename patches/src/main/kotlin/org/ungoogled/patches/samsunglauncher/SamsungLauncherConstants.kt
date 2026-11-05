package org.ungoogled.patches.samsunglauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungLauncherConstants {
    /**
     * One UI Home is a single APK from the Galaxy Store. Pinned to 18.0.07.59, the newest that runs on Android 15:
     * R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_LAUNCHER = Compatibility(
        name = "One UI Home",
        packageName = "com.sec.android.app.launcher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3E91FF,
        targets = listOf(
            AppTarget(version = "18.0.07.59"),
        ),
    )
}
