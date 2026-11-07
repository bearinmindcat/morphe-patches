package org.ungoogled.patches.sonymusic

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SonyMusicConstants {
    /** Sony Music (Xperia's music player) is a single APK. Pinned to the version tested. */
    val COMPATIBILITY_SONY_MUSIC = Compatibility(
        name = "Sony Music",
        packageName = "com.sonyericsson.music",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x5E35B1,
        targets = listOf(
            AppTarget(version = "9.4.16.A.1.17", minSdk = 30, description = "needs Android 11 or newer"),
        ),
    )
}
