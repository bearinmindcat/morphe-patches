package org.ungoogled.patches.samsungwonderland

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungWonderlandConstants {
    /** Wonderland, the Good Lock module for 3D motion wallpapers. R8 renames its classes every build. */
    val COMPATIBILITY_SAMSUNG_WONDERLAND = Compatibility(
        name = "Wonderland",
        packageName = "com.samsung.android.wonderland.wallpaper",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x5B6CF0,
        targets = listOf(
            AppTarget(version = "1.6.22"),
        ),
    )
}
