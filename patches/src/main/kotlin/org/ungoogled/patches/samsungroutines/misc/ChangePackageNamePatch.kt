package org.ungoogled.patches.samsungroutines.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungroutines.SamsungRoutinesConstants.COMPATIBILITY_SAMSUNG_ROUTINES

/** Installs the port next to Samsung's own Modes and Routines on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_ROUTINES,
    stock = "com.samsung.android.app.routines",
    defaultName = "org.ungoogled.samsungroutines",
    appName = "Modes and Routines",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 38 permissions + authorities.
    minimumNames = 28,
)
