package org.ungoogled.patches.coloroslauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsLauncherConstants {
    /**
     * OnePlus's System Launcher (ColorOS / OxygenOS 16) is a single APK. Pinned to the version tested: R8 renames its
     * classes every build.
     */
    val COMPATIBILITY_COLOROS_LAUNCHER = Compatibility(
        name = "OnePlus System Launcher",
        packageName = "com.android.launcher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1796FE,
        targets = listOf(
            AppTarget(version = "16.4.30"),
        ),
    )
}
