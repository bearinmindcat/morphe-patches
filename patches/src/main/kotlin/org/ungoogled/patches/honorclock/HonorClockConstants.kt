package org.ungoogled.patches.honorclock

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorClockConstants {
    /** Honor Clock is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_CLOCK = Compatibility(
        name = "Honor Clock",
        packageName = "com.hihonor.deskclock",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x256FFF,
        targets = listOf(
            AppTarget(version = "12.0.1.196"),
        ),
    )
}
