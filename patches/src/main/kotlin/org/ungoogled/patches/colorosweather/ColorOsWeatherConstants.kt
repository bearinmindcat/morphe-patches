package org.ungoogled.patches.colorosweather

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsWeatherConstants {
    /** OnePlus Weather is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_WEATHER = Compatibility(
        name = "OnePlus Weather",
        packageName = "net.oneplus.weather",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3998FB,
        targets = listOf(
            AppTarget(version = "17.6.30"),
        ),
    )
}
