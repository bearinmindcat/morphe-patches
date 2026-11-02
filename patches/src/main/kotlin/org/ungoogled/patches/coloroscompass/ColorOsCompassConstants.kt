package org.ungoogled.patches.coloroscompass

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsCompassConstants {
    /** OnePlus / OPPO Compass is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_COMPASS = Compatibility(
        name = "OnePlus Compass",
        packageName = "com.coloros.compass2",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x202035,
        targets = listOf(
            AppTarget(version = "17.0.0"),
        ),
    )
}
