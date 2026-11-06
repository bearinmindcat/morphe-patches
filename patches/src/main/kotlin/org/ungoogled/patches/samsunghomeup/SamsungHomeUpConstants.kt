package org.ungoogled.patches.samsunghomeup

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungHomeUpConstants {
    /**
     * Home Up, the Good Lock module that customizes One UI Home, is a single APK from the Galaxy Store. Pinned to
     * 18.0.00.24, the Home Up for One UI Home 18.0 (the version the One UI Home port runs): R8 renames its classes
     * every build.
     */
    val COMPATIBILITY_SAMSUNG_HOME_UP = Compatibility(
        name = "Home Up",
        packageName = "com.samsung.android.app.homestar",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3E91FF,
        targets = listOf(
            AppTarget(version = "18.0.00.24"),
        ),
    )
}
