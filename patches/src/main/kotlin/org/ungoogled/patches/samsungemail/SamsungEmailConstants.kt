package org.ungoogled.patches.samsungemail

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungEmailConstants {
    /** Samsung Email is a single APK from the Galaxy Store. Pinned to 6.2.13.1: R8 renames its classes every build. */
    val COMPATIBILITY_SAMSUNG_EMAIL = Compatibility(
        name = "Samsung Email",
        packageName = "com.samsung.android.email.provider",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF31C1C,
        targets = listOf(
            AppTarget(version = "6.2.13.1"),
        ),
    )
}
