package org.ungoogled.patches.honorscreenrecorder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorScreenRecorderConstants {
    /** Honor Screen recording is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_SCREEN_RECORDER = Compatibility(
        name = "Honor Screen recording",
        packageName = "com.hihonor.screenrecorder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF0503C,
        targets = listOf(
            AppTarget(version = "14.0.1.181"),
        ),
    )
}
