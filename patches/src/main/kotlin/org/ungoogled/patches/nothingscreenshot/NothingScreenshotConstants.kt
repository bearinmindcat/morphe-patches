package org.ungoogled.patches.nothingscreenshot

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object NothingScreenshotConstants {
    /** Nothing Screenshot (com.nothing.ntscreenshot) is a single APK from APKMirror. Pinned to the version tested. */
    val COMPATIBILITY_NOTHING_SCREENSHOT = Compatibility(
        name = "Nothing Screenshot",
        packageName = "com.nothing.ntscreenshot",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xD71921,
        targets = listOf(
            AppTarget(version = "14.0.0", minSdk = 34, description = "needs Android 14 or newer"),
        ),
    )
}
