package org.ungoogled.patches.samsungweather

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungWeatherConstants {
    /**
     * Samsung Weather ("Unified Daemon", com.sec.android.daemonapp) is a single APK from the Galaxy Store. Pinned to
     * 1.7.30.50.
     */
    val COMPATIBILITY_SAMSUNG_WEATHER = Compatibility(
        name = "Samsung Weather",
        packageName = "com.sec.android.daemonapp",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x00A2FF,
        targets = listOf(
            AppTarget(version = "1.7.30.50"),
        ),
    )
}
