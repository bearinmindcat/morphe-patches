package org.ungoogled.patches.honorcalendar

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorCalendarConstants {
    /** Honor Calendar ("Agenda") is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_CALENDAR = Compatibility(
        name = "Honor Calendar",
        packageName = "com.hihonor.calendar",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x2798FF,
        targets = listOf(
            AppTarget(version = "13.3.0.345"),
        ),
    )
}
