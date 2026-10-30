package org.ungoogled.patches.samsunghealthmonitor

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungHealthMonitorConstants {
    /**
     * Samsung Health Monitor (phone app) is a single APK from the Galaxy Store. Pinned to 1.5.3.011: R8 renames its
     * classes every build.
     */
    val COMPATIBILITY_SAMSUNG_HEALTH_MONITOR = Compatibility(
        name = "Samsung Health Monitor",
        packageName = "com.samsung.android.shealthmonitor",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF0466A,
        targets = listOf(
            AppTarget(version = "1.5.3.011"),
        ),
    )
}
