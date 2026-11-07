package org.ungoogled.patches.xiaomilauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiLauncherConstants {
    /**
     * The HyperOS launcher (Xiaomi's System launcher, com.miui.home) of HyperOS 2: a single APK. Pinned to the version
     * tested: R8 renames its classes every build. HyperOS 3 builds carry no code (it lives in the firmware) and can't be
     * patched.
     */
    val COMPATIBILITY_XIAOMI_LAUNCHER = Compatibility(
        name = "HyperOS Launcher",
        packageName = "com.miui.home",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF6900,
        targets = listOf(
            AppTarget(version = "RELEASE-7.50.06.2372-06261924", minSdk = 34, description = "needs Android 14 or newer"),
        ),
    )
}
