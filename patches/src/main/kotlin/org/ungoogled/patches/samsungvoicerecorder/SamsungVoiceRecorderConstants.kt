package org.ungoogled.patches.samsungvoicerecorder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungVoiceRecorderConstants {
    /** Samsung Voice Recorder is a single APK from the Galaxy Store. Pinned to 21.5.91.33: R8 renames its classes every build. */
    val COMPATIBILITY_SAMSUNG_VOICE_RECORDER = Compatibility(
        name = "Samsung Voice Recorder",
        packageName = "com.sec.android.app.voicenote",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF0473E,
        targets = listOf(
            AppTarget(version = "21.5.91.33"),
        ),
    )
}
