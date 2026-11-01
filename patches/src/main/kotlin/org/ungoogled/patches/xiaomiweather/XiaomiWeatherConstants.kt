package org.ungoogled.patches.xiaomiweather

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiWeatherConstants {
    /**
     * Xiaomi Weather (Global) is a single APK. Pinned to the version tested: R8 renames its classes every build. The
     * newer 17.1 builds are HyperOS-only shells whose code lives in the system, so there is nothing to patch there.
     */
    val COMPATIBILITY_XIAOMI_WEATHER = Compatibility(
        name = "Xiaomi Weather",
        packageName = "com.miui.weather2",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x42A6F1,
        targets = listOf(
            AppTarget(version = "G-17.0.3.26"),
        ),
    )
}
