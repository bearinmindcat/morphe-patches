package org.ungoogled.patches.motolauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object MotoLauncherConstants {
    /**
     * Moto App Launcher (Motorola's home screen) is a single APK. Pinned to the versions tested: R8 renames its classes
     * every build. Version 15 is the Android 15 build, 13 the one for Android 13 and 14 (newest first: the version check
     * suggests the first one a phone can run).
     */
    val COMPATIBILITY_MOTO_LAUNCHER = Compatibility(
        name = "Moto App Launcher",
        packageName = "com.motorola.launcher3",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x5C2D91,
        targets = listOf(
            AppTarget(version = "15", minSdk = 35, description = "needs Android 15 or newer"),
            AppTarget(version = "13", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
