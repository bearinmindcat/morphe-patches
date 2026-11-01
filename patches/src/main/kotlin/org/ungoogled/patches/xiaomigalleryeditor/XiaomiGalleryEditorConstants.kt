package org.ungoogled.patches.xiaomigalleryeditor

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiGalleryEditorConstants {
    /** Xiaomi Gallery Editor (Global) is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_XIAOMI_GALLERY_EDITOR = Compatibility(
        name = "Xiaomi Gallery Editor",
        packageName = "com.miui.mediaeditor",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x7683FF,
        targets = listOf(
            AppTarget(version = "2.10.40.4.8-global"),
        ),
    )
}
