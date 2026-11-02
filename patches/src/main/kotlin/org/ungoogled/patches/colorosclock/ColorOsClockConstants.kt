package org.ungoogled.patches.colorosclock

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsClockConstants {
    /** OnePlus Clock is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_CLOCK = Compatibility(
        name = "OnePlus Clock",
        packageName = "com.oneplus.deskclock",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xD44539,
        targets = listOf(
            AppTarget(version = "16.19.8"),
        ),
    )
}
