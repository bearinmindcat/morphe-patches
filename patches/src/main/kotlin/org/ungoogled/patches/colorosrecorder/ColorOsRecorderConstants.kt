package org.ungoogled.patches.colorosrecorder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsRecorderConstants {
    /** OnePlus Recorder is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_RECORDER = Compatibility(
        name = "OnePlus Recorder",
        packageName = "com.oneplus.soundrecorder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF2738,
        targets = listOf(
            AppTarget(version = "16.17.6"),
        ),
    )
}
