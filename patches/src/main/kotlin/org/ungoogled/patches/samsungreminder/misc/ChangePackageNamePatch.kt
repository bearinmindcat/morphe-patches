package org.ungoogled.patches.samsungreminder.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungreminder.SamsungReminderConstants.COMPATIBILITY_SAMSUNG_REMINDER

/** Installs the port next to Samsung's own Reminder on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_REMINDER,
    stock = "com.samsung.android.app.reminder",
    defaultName = "org.ungoogled.samsungreminder",
    appName = "Reminder",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 24 permissions + authorities.
    minimumNames = 18,
)
