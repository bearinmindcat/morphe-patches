package org.ungoogled.patches.samsungonehand.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungonehand.SamsungOneHandConstants.COMPATIBILITY_SAMSUNG_ONE_HAND

/** Installs the port next to Samsung's own One Hand Operation + on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_ONE_HAND,
    stock = "com.samsung.android.sidegesturepad",
    defaultName = "org.ungoogled.sidegesturepad",
    appName = "One Hand Operation +",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 4 permissions + authorities.
    minimumNames = 3,
)
