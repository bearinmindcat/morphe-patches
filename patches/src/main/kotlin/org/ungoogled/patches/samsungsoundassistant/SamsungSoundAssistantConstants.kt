package org.ungoogled.patches.samsungsoundassistant

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungSoundAssistantConstants {
    /**
     * Sound Assistant is a single APK from the Galaxy Store. Pinned to 8.1.00.6, the newest that runs on Android 15
     * (8.2 needs Android 16): R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_SOUND_ASSISTANT = Compatibility(
        name = "Sound Assistant",
        packageName = "com.samsung.android.soundassistant",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3E91FF,
        targets = listOf(
            AppTarget(version = "8.1.00.6"),
        ),
    )
}
