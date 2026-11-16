package org.ungoogled.patches.samsungonehand

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungOneHandConstants {
    /**
     * One Hand Operation +, the Good Lock module for edge-swipe gestures. R8 renames its classes every build. 9.3.13.0
     * asks for Android 14; the patch lets it run on Android 13 too.
     */
    val COMPATIBILITY_SAMSUNG_ONE_HAND = Compatibility(
        name = "One Hand Operation +",
        packageName = "com.samsung.android.sidegesturepad",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x22BDC5,
        targets = listOf(
            AppTarget(version = "9.3.13.0", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
