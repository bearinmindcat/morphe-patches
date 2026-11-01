package org.ungoogled.patches.honorcalculator

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorCalculatorConstants {
    /** Honor Calculator is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_CALCULATOR = Compatibility(
        name = "Honor Calculator",
        packageName = "com.hihonor.calculator",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x2798FF,
        targets = listOf(
            AppTarget(version = "12.0.1.217"),
        ),
    )
}
