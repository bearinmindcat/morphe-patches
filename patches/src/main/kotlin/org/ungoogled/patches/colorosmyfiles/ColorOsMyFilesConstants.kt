package org.ungoogled.patches.colorosmyfiles

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsMyFilesConstants {
    /** OnePlus My Files is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_MY_FILES = Compatibility(
        name = "OnePlus My Files",
        packageName = "com.oneplus.filemanager",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF1B742,
        targets = listOf(
            AppTarget(version = "16.12.4"),
        ),
    )
}
