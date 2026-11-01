package org.ungoogled.patches.lgquickmemo

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object LgQuickMemoConstants {
    /** LG QuickMemo+ is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_LG_QUICKMEMO = Compatibility(
        name = "LG QuickMemo+",
        packageName = "com.lge.qmemoplus",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF5A623,
        targets = listOf(
            AppTarget(version = "10.0.30"),
        ),
    )
}
