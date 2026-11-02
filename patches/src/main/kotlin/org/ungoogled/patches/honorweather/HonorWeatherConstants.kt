package org.ungoogled.patches.honorweather

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorWeatherConstants {
    /** Honor Weather is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_WEATHER = Compatibility(
        name = "Honor Weather",
        packageName = "com.hihonor.android.totemweather",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x2798FF,
        targets = listOf(
            AppTarget(version = "18.1.2.311"),
        ),
    )
}
