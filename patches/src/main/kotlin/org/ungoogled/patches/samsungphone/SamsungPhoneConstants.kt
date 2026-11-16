package org.ungoogled.patches.samsungphone

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungPhoneConstants {
    /**
     * Samsung Phone is a single APK from the Galaxy Store. Pinned to 16.3.52.1: R8 renames its classes every build. It
     * asks for Android 15; the patch lets it run on Android 13 too.
     */
    val COMPATIBILITY_SAMSUNG_PHONE = Compatibility(
        name = "Samsung Phone",
        packageName = "com.samsung.android.dialer",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x0CC251,
        targets = listOf(
            AppTarget(version = "16.3.52.1", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
