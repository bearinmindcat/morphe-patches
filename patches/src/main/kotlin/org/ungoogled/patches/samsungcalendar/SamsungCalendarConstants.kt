package org.ungoogled.patches.samsungcalendar

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungCalendarConstants {
    /**
     * Samsung Calendar is a single APK from the Galaxy Store. Pinned to 12.8.01.0: R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_CALENDAR = Compatibility(
        name = "Samsung Calendar",
        packageName = "com.samsung.android.calendar",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x06BEB0,
        targets = listOf(
            AppTarget(version = "12.8.01.0"),
        ),
    )
}
