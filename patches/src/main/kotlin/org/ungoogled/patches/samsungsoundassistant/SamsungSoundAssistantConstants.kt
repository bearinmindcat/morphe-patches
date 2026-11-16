package org.ungoogled.patches.samsungsoundassistant

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungSoundAssistantConstants {
    /**
     * Sound Assistant is a single APK from the Galaxy Store. Pinned to 8.1.00.6 (8.2 needs Android 16): R8 renames
     * its classes every build. It asks for Android 15; the patch lets it run on Android 13 too.
     */
    val COMPATIBILITY_SAMSUNG_SOUND_ASSISTANT = Compatibility(
        name = "Sound Assistant",
        packageName = "com.samsung.android.soundassistant",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3E91FF,
        targets = listOf(
            AppTarget(version = "8.1.00.6", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
