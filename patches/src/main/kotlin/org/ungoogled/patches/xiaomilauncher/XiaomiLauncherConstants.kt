package org.ungoogled.patches.xiaomilauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiLauncherConstants {
    /**
     * The HyperOS launcher (Xiaomi's System launcher, com.miui.home) of HyperOS 2: a single APK. Pinned to the versions
     * tested: R8 renames its classes every build. 7.50.06.2372 is HyperOS 2's (Android 14 and newer), 5.39.40.11727 the
     * newest build for Android 13 (newest first: the version check suggests the first one a phone can run). HyperOS 3
     * builds carry no code (it lives in the firmware) and can't be patched.
     */
    val COMPATIBILITY_XIAOMI_LAUNCHER = Compatibility(
        name = "HyperOS Launcher",
        packageName = "com.miui.home",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF6900,
        targets = listOf(
            AppTarget(version = "RELEASE-7.50.06.2372-06261924", minSdk = 34, description = "needs Android 14 or newer"),
            AppTarget(version = "RELEASE-5.39.40.11727-12151939", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
