package org.ungoogled.patches.samsungwonderland.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungwonderland.SamsungWonderlandConstants.COMPATIBILITY_SAMSUNG_WONDERLAND

/** Installs the port next to Samsung's own Wonderland on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_WONDERLAND,
    stock = "com.samsung.android.wonderland.wallpaper",
    defaultName = "org.ungoogled.wonderland",
    appName = "Wonderland",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 7 permissions + authorities.
    minimumNames = 5,
)
