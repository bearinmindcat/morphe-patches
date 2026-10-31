package org.ungoogled.patches.samsungclock

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungClockConstants {
    /**
     * Samsung Clock is a single APK from the Galaxy Store. Pinned to 12.4.02.38, the newest that runs on Android 15
     * (12.5 needs Android 16): R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_CLOCK = Compatibility(
        name = "Samsung Clock",
        packageName = "com.sec.android.app.clockpackage",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3E91FF,
        targets = listOf(
            AppTarget(version = "12.4.02.38"),
        ),
    )
}
