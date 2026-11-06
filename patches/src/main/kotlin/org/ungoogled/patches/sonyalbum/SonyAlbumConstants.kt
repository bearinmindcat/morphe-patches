package org.ungoogled.patches.sonyalbum

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SonyAlbumConstants {
    /** Sony Album (Xperia's gallery) is a single APK. Pinned to the version tested: R8 renames classes every build. */
    val COMPATIBILITY_SONY_ALBUM = Compatibility(
        name = "Sony Album",
        packageName = "com.sonyericsson.album",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF57C00,
        targets = listOf(
            AppTarget(version = "9.9.A.0.20"),
        ),
    )
}
