package org.ungoogled.patches.samsungmessages

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungMessagesConstants {
    /**
     * Samsung Messages is a single APK from the Galaxy Store. Pinned to 16.3.21.1: R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_MESSAGES = Compatibility(
        name = "Samsung Messages",
        packageName = "com.samsung.android.messaging",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x20AFFF,
        targets = listOf(
            AppTarget(version = "16.3.21.1"),
        ),
    )
}
