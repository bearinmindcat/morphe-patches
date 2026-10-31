package org.ungoogled.patches.samsungphotoeditor

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungPhotoEditorConstants {
    /**
     * Photo Editor is a single APK from the Galaxy Store. Pinned to 3.9.04.6, the newest that runs on Android 15
     * (3.9.09 needs Android 16): R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_PHOTO_EDITOR = Compatibility(
        name = "Samsung Photo Editor",
        packageName = "com.sec.android.mimage.photoretouching",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFE0053,
        targets = listOf(
            AppTarget(version = "3.9.04.6"),
        ),
    )
}
