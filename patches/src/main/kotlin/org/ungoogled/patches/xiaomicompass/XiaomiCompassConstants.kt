package org.ungoogled.patches.xiaomicompass

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiCompassConstants {
    /** Xiaomi Compass is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_XIAOMI_COMPASS = Compatibility(
        name = "Xiaomi Compass",
        packageName = "com.miui.compass",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x93B1C8,
        targets = listOf(
            AppTarget(version = "17.1.4.1"),
        ),
    )
}
