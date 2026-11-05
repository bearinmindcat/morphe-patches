package org.ungoogled.patches.samsunghealth

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungHealthConstants {
    /**
     * Samsung Health is a single APK (Play Store / Galaxy Store) and already runs on any phone. Pinned to the versions
     * checked: R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_HEALTH = Compatibility(
        name = "Samsung Health",
        packageName = "com.sec.android.app.shealth",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x07AADD,
        targets = listOf(
            AppTarget(version = "7.1.3.001"),
            AppTarget(version = "7.00.6.011"),
        ),
    )
}
