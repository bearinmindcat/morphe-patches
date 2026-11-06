package org.ungoogled.patches.samsunggoodlock

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungGoodLockConstants {
    /** Good Lock, the hub that lists and opens Samsung's customization modules. R8 renames its classes every build. */
    val COMPATIBILITY_SAMSUNG_GOOD_LOCK = Compatibility(
        name = "Good Lock",
        packageName = "com.samsung.android.goodlock",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF24A8C,
        targets = listOf(
            AppTarget(version = "3.0.16.6"),
        ),
    )
}
