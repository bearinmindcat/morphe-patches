package org.ungoogled.patches.samsungmusic

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungMusicConstants {
    /**
     * Samsung Music is a single APK from the Galaxy Store. Pinned to 16.2.45.1: R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_MUSIC = Compatibility(
        name = "Samsung Music",
        packageName = "com.sec.android.app.music",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x6522E9,
        targets = listOf(
            AppTarget(version = "16.2.45.1"),
        ),
    )
}
