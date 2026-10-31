package org.ungoogled.patches.samsungvideoplayer

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungVideoPlayerConstants {
    /**
     * Video Player is a single APK from the Galaxy Store. Pinned to 7.3.47.1, the Galaxy Store's build for Android 15:
     * R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_VIDEO_PLAYER = Compatibility(
        name = "Samsung Video Player",
        packageName = "com.samsung.android.video",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x9A10F0,
        targets = listOf(
            AppTarget(version = "7.3.47.1"),
        ),
    )
}
