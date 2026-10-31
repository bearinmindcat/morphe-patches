package org.ungoogled.patches.samsungcalculator

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungCalculatorConstants {
    /**
     * Samsung Calculator is a single APK from the Galaxy Store. Pinned to 12.4.00.13, the newest that runs on Android
     * 15 (12.5 needs Android 16): R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_CALCULATOR = Compatibility(
        name = "Samsung Calculator",
        packageName = "com.sec.android.app.popupcalculator",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3E91FF,
        targets = listOf(
            AppTarget(version = "12.4.00.13"),
        ),
    )
}
