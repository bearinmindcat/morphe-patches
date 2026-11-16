package org.ungoogled.patches.samsungcapture

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungCaptureConstants {
    /**
     * Samsung capture (screenshots and screen recording) is a single APK. Pinned to 6.2.12, the build the patches were
     * made against. It asks for Android 15; the patch lets it run on Android 13 too.
     */
    val COMPATIBILITY_SAMSUNG_CAPTURE = Compatibility(
        name = "Samsung Capture",
        packageName = "com.samsung.android.app.smartcapture",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF12828,
        targets = listOf(
            AppTarget(version = "6.2.12", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
