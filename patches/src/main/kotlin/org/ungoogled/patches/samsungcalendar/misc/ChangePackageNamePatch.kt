package org.ungoogled.patches.samsungcalendar.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungcalendar.SamsungCalendarConstants.COMPATIBILITY_SAMSUNG_CALENDAR

/** Installs the port next to Samsung's own Calendar on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_CALENDAR,
    stock = "com.samsung.android.calendar",
    defaultName = "org.ungoogled.samsungcalendar",
    appName = "Calendar",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 33 (12.5.06.5: 29) permissions + authorities.
    minimumNames = 22,
)
