package org.ungoogled.patches.honorrecorder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorRecorderConstants {
    /** Honor Recorder is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_RECORDER = Compatibility(
        name = "Honor Recorder",
        packageName = "com.hihonor.soundrecorder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF02020,
        targets = listOf(
            AppTarget(version = "14.0.1.197"),
        ),
    )
}
