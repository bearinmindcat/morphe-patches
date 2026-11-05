package org.ungoogled.patches.waze

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object WazeConstants {
    /**
     * Waze from the Play Store: `base.apk` plus ABI, density and language splits. The
     * patches find what they touch by strings, JNI names and shapes, since every build
     * renames the obfuscated classes; each listed version has been checked.
     */
    val COMPATIBILITY_WAZE = Compatibility(
        name = "Waze",
        packageName = "com.waze",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x33CCFF,
        targets = listOf(
            AppTarget(version = "5.27.0.1"),
            AppTarget(version = "5.26.90.901"),
            AppTarget(version = "5.26.5.0"),
            AppTarget(version = "5.24.5.0"),
        ),
    )
}
