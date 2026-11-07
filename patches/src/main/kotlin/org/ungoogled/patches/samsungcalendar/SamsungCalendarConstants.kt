package org.ungoogled.patches.samsungcalendar

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungCalendarConstants {
    /**
     * Samsung Calendar is a single APK from the Galaxy Store. Pinned to versions measured: R8 renames its classes every
     * build. 12.8 needs Android 15, 12.5.06.5 (issue #92) Android 13.
     */
    val COMPATIBILITY_SAMSUNG_CALENDAR = Compatibility(
        name = "Samsung Calendar",
        packageName = "com.samsung.android.calendar",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x06BEB0,
        targets = listOf(
            AppTarget(version = "12.8.01.0", minSdk = 35, description = "needs Android 15 or newer"),
            AppTarget(version = "12.5.06.5", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
