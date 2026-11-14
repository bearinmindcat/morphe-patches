package org.ungoogled.patches.photos

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object PhotosConstants {
    /**
     * Google Photos from the Play Store: `base.apk` plus ABI, density and language splits. The patches find what
     * they touch by strings, un-obfuscated names and shapes, since every build renames the obfuscated classes;
     * each listed version has been checked.
     */
    val COMPATIBILITY_PHOTOS = Compatibility(
        name = "Google Photos",
        packageName = "com.google.android.apps.photos",
        apkFileType = ApkFileType.APKM,
        // The launcher icon's background plate is white.
        appIconColor = 0xFFFFFF,
        targets = listOf(
            AppTarget(version = "8.1.0.1016919706"),
        ),
    )

    /** org.ungoogled.photos, the runtime half (extensions/photos). */
    const val PHOTOS_EXTENSION = "extensions/photos.mpe"
}
