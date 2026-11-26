package org.ungoogled.patches.samsungfinder.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungfinder.SamsungFinderConstants.COMPATIBILITY_SAMSUNG_FINDER

/** Installs the port next to Samsung's own Finder on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_FINDER,
    stock = "com.samsung.android.app.galaxyfinder",
    defaultName = "org.ungoogled.galaxyfinder",
    appName = "Finder",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 16 permissions + authorities.
    minimumNames = 12,
)
