package org.ungoogled.patches.colorosscreenrecorder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsScreenRecorderConstants {
    /** ColorOS's Screen recording is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_SCREEN_RECORDER = Compatibility(
        name = "OnePlus Screen Recording",
        packageName = "com.oplus.screenrecorder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xEB3B30,
        targets = listOf(
            AppTarget(version = "16.5.12"),
        ),
    )
}
