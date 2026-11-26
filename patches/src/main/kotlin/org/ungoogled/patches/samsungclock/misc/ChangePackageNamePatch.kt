package org.ungoogled.patches.samsungclock.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungclock.SamsungClockConstants.COMPATIBILITY_SAMSUNG_CLOCK

/** Installs the port next to Samsung's own Clock on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_CLOCK,
    stock = "com.sec.android.app.clockpackage",
    defaultName = "org.ungoogled.clockpackage",
    appName = "Clock",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 32 permissions + authorities.
    minimumNames = 24,
    // Its alarm actions live under another name (com.samsung.sec.android.clockpackage.alarm.*).
    privateActionPrefixes = listOf("com.samsung.sec.android.clockpackage."),
)
