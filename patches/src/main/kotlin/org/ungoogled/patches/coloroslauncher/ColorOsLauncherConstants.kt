package org.ungoogled.patches.coloroslauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsLauncherConstants {
    /**
     * OnePlus's System Launcher is a single APK: 16.4.30 (ColorOS / OxygenOS 16) for Android 13 and newer, 15.6.24
     * (ColorOS 15) for Android 11 and 12. Pinned to the versions tested: R8 renames its classes every build. Newest
     * first (the version check suggests the first one the phone can run).
     */
    val COMPATIBILITY_COLOROS_LAUNCHER = Compatibility(
        name = "OnePlus System Launcher",
        packageName = "com.android.launcher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1796FE,
        targets = listOf(
            AppTarget(version = "16.4.30", minSdk = 33, description = "needs Android 13 or newer"),
            AppTarget(version = "15.6.24", minSdk = 30, description = "needs Android 11 or newer"),
        ),
    )
}
