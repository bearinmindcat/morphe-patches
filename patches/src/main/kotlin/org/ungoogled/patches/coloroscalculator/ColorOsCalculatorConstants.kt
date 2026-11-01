package org.ungoogled.patches.coloroscalculator

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsCalculatorConstants {
    /** OnePlus Calculator is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_CALCULATOR = Compatibility(
        name = "OnePlus Calculator",
        packageName = "com.oneplus.calculator",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFA6731,
        targets = listOf(
            AppTarget(version = "17.0.0"),
        ),
    )
}
