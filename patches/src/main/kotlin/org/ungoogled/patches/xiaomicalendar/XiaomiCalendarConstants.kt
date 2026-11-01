package org.ungoogled.patches.xiaomicalendar

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiCalendarConstants {
    /** Xiaomi Calendar is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_XIAOMI_CALENDAR = Compatibility(
        name = "Xiaomi Calendar",
        packageName = "com.android.calendar",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3482FF,
        targets = listOf(
            AppTarget(version = "18.0.2.0"),
        ),
    )
}
