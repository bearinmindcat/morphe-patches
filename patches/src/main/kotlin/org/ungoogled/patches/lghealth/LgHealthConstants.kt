package org.ungoogled.patches.lghealth

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object LgHealthConstants {
    /** LG Health is a single APK. Pinned to the version tested. */
    val COMPATIBILITY_LG_HEALTH = Compatibility(
        name = "LG Health",
        packageName = "com.lge.lifetracker",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1CDDBF,
        targets = listOf(
            AppTarget(version = "5.60.16"),
        ),
    )
}
