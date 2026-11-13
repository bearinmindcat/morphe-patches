package org.ungoogled.patches.samsungeditorlite

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungEditorLiteConstants {
    /**
     * Editor Lite (formerly Video Trimmer) is a single APK. Pinned to 4.0.85.20, the newest build for Android 14 and
     * 15 (4.0.90 needs Android 16): R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_EDITOR_LITE = Compatibility(
        name = "Samsung Editor Lite",
        packageName = "com.samsung.app.newtrim",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF52B5F,
        targets = listOf(
            AppTarget(version = "4.0.85.20", minSdk = 34, description = "needs Android 14 or newer"),
        ),
    )
}
