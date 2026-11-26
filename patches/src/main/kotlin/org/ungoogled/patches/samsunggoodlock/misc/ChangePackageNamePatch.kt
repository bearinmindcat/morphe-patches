package org.ungoogled.patches.samsunggoodlock.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsunggoodlock.SamsungGoodLockConstants.COMPATIBILITY_SAMSUNG_GOOD_LOCK

/** Installs the port next to Samsung's own Good Lock on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_GOOD_LOCK,
    stock = "com.samsung.android.goodlock",
    defaultName = "org.ungoogled.goodlock",
    appName = "Good Lock",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 4 permissions + authorities.
    minimumNames = 3,
)
