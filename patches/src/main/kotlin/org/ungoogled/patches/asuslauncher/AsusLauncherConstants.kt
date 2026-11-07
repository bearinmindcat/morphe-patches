package org.ungoogled.patches.asuslauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object AsusLauncherConstants {
    /**
     * ASUS Launcher (ZenUI's home screen) is a single APK. Pinned to the version tested: R8 renames its classes every
     * build. It needs Android 13 or later.
     */
    val COMPATIBILITY_ASUS_LAUNCHER = Compatibility(
        name = "ASUS Launcher",
        packageName = "com.asus.launcher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1E88E5,
        targets = listOf(
            AppTarget(version = "11.0.0.76_240920", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
