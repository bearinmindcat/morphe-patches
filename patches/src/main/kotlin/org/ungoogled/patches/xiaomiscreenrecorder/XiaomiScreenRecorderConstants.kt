package org.ungoogled.patches.xiaomiscreenrecorder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiScreenRecorderConstants {
    /** Xiaomi Screen Recorder is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_XIAOMI_SCREEN_RECORDER = Compatibility(
        name = "Xiaomi Screen Recorder",
        packageName = "com.miui.screenrecorder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF5A36,
        targets = listOf(
            AppTarget(version = "4.16.2.9.1"),
        ),
    )
}
