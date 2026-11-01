package org.ungoogled.patches.nothingrecorder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object NothingRecorderConstants {
    /**
     * Nothing Recorder (com.nothing.soundrecorder) is a single APK from APKMirror. Pinned to the version tested: it
     * is R8-obfuscated, so its class names change every build.
     */
    val COMPATIBILITY_NOTHING_RECORDER = Compatibility(
        name = "Nothing Recorder",
        packageName = "com.nothing.soundrecorder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xD71921,
        targets = listOf(
            AppTarget(version = "4.0.0"),
        ),
    )
}
