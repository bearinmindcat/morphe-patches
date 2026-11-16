package org.ungoogled.patches.samsungroutines

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungRoutinesConstants {
    /**
     * Modes and Routines is a single APK from the Galaxy Store. Pinned to 5.1.03.1: R8 renames its classes every build.
     * It asks for Android 15; the patch lets it run on Android 13 too.
     */
    val COMPATIBILITY_SAMSUNG_ROUTINES = Compatibility(
        name = "Samsung Modes and Routines",
        packageName = "com.samsung.android.app.routines",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x436BEF,
        targets = listOf(
            AppTarget(version = "5.1.03.1", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
