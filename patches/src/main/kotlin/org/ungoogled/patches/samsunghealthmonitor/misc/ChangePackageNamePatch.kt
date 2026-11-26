package org.ungoogled.patches.samsunghealthmonitor.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsunghealthmonitor.SamsungHealthMonitorConstants.COMPATIBILITY_SAMSUNG_HEALTH_MONITOR

/** Installs the port next to Samsung's own Health Monitor on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_HEALTH_MONITOR,
    stock = "com.samsung.android.shealthmonitor",
    defaultName = "org.ungoogled.shealthmonitor",
    appName = "Health Monitor",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 9 permissions + authorities.
    minimumNames = 6,
)
