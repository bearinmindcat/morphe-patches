package org.ungoogled.patches.onepluslauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object OnePlusLauncherConstants {
    /**
     * The old OnePlus Launcher (net.oneplus.launcher) of OxygenOS 11, the last one published as an app (2021). A single
     * APK, pinned to the version tested.
     */
    val COMPATIBILITY_ONEPLUS_LAUNCHER = Compatibility(
        name = "OnePlus Launcher (OxygenOS 11)",
        packageName = "net.oneplus.launcher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xEB0028,
        targets = listOf(
            AppTarget(version = "5.2.66"),
        ),
    )
}
