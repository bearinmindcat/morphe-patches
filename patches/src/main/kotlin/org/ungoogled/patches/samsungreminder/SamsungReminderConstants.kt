package org.ungoogled.patches.samsungreminder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungReminderConstants {
    /**
     * Samsung Reminder is a single APK from the Galaxy Store. Pinned to 12.8.00.62: R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_REMINDER = Compatibility(
        name = "Samsung Reminder",
        packageName = "com.samsung.android.app.reminder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x410CB7,
        targets = listOf(
            AppTarget(version = "12.8.00.62"),
        ),
    )
}
