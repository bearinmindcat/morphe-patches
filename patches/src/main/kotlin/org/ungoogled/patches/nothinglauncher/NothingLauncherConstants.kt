package org.ungoogled.patches.nothinglauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object NothingLauncherConstants {
    /**
     * Nothing Launcher (com.nothing.launcher) is a split bundle from APKMirror (APKM). Pinned to the versions tested:
     * it is R8-obfuscated, so its class names change every build. 4.0.20 needs Android 16 or later; 3.3.14, the newest
     * build for Android 15, is there for phones on Android 15 (newest first: the version check suggests the first one a
     * phone can run).
     */
    val COMPATIBILITY_NOTHING_LAUNCHER = Compatibility(
        name = "Nothing Launcher",
        packageName = "com.nothing.launcher",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xD71921,
        targets = listOf(
            AppTarget(version = "4.0.20", minSdk = 36, description = "needs Android 16 or newer"),
            AppTarget(version = "3.3.14", minSdk = 35, description = "needs Android 15 or newer"),
        ),
    )
}
