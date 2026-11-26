package org.ungoogled.patches.samsungvideolibrary.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungvideolibrary.SamsungVideoLibraryConstants.COMPATIBILITY_SAMSUNG_VIDEO_LIBRARY

/** Installs the port next to Samsung's own Video Library on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_VIDEO_LIBRARY,
    stock = "com.samsung.android.videolist",
    defaultName = "org.ungoogled.videolist",
    appName = "Video Library",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 5 permissions + authorities.
    minimumNames = 4,
)
