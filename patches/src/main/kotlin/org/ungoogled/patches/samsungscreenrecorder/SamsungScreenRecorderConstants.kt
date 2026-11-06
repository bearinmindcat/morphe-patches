package org.ungoogled.patches.samsungscreenrecorder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungScreenRecorderConstants {
    /** Samsung Screen Recorder ("Record screen") is a single APK. Pinned to 4.0.23, the build the patches were made against. */
    val COMPATIBILITY_SAMSUNG_SCREEN_RECORDER = Compatibility(
        name = "Samsung Screen Recorder",
        packageName = "com.sec.app.screenrecorder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x9A80FF,
        targets = listOf(
            AppTarget(version = "4.0.23"),
        ),
    )
}
