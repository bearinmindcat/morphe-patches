package org.ungoogled.patches.xiaomirecorder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiRecorderConstants {
    /** Xiaomi Recorder is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_XIAOMI_RECORDER = Compatibility(
        name = "Xiaomi Recorder",
        packageName = "com.android.soundrecorder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF4848,
        targets = listOf(
            AppTarget(version = "7.7.9"),
        ),
    )
}
