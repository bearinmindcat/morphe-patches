package org.ungoogled.patches.honorvideoplayer

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorVideoPlayerConstants {
    /** Honor Video Player is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_VIDEO_PLAYER = Compatibility(
        name = "Honor Video Player",
        packageName = "com.hihonor.hnvideoplayer",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x2798FF,
        targets = listOf(
            AppTarget(version = "8.0.0.032"),
        ),
    )
}
