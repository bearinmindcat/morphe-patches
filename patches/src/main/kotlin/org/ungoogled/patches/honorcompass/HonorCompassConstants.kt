package org.ungoogled.patches.honorcompass

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorCompassConstants {
    /** Honor Compass is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_COMPASS = Compatibility(
        name = "Honor Compass",
        packageName = "com.hihonor.compass",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x0078D8,
        targets = listOf(
            AppTarget(version = "14.0.1.174"),
        ),
    )
}
