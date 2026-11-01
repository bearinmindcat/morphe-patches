package org.ungoogled.patches.xiaomiscanner

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiScannerConstants {
    /** Xiaomi Scanner is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_XIAOMI_SCANNER = Compatibility(
        name = "Xiaomi Scanner",
        packageName = "com.xiaomi.scanner",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x252B32,
        targets = listOf(
            AppTarget(version = "15.9.78"),
        ),
    )
}
