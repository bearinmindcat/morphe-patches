package org.ungoogled.patches.honorhome

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorHomeConstants {
    /** Honor Home is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_HOME = Compatibility(
        name = "Honor Home",
        packageName = "com.hihonor.android.launcher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x256FFF,
        targets = listOf(
            AppTarget(version = "14.0.0.210"),
        ),
    )
}
