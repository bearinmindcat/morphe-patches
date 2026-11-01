package org.ungoogled.patches.xiaomigallery

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiGalleryConstants {
    /** Xiaomi Gallery (Global) is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_XIAOMI_GALLERY = Compatibility(
        name = "Xiaomi Gallery",
        packageName = "com.miui.gallery",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x7683FF,
        targets = listOf(
            AppTarget(version = "4.3.1.15-global"),
        ),
    )
}
