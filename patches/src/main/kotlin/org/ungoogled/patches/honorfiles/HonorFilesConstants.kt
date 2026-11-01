package org.ungoogled.patches.honorfiles

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorFilesConstants {
    /** Honor Files is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_FILES = Compatibility(
        name = "Honor Files",
        packageName = "com.hihonor.filemanager",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x2798FF,
        targets = listOf(
            AppTarget(version = "10.0.0.641"),
        ),
    )
}
