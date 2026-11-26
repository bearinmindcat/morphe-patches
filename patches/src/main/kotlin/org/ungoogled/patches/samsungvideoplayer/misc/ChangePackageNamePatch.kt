package org.ungoogled.patches.samsungvideoplayer.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungvideoplayer.SamsungVideoPlayerConstants.COMPATIBILITY_SAMSUNG_VIDEO_PLAYER

/** Installs the port next to Samsung's own Video Player on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_VIDEO_PLAYER,
    stock = "com.samsung.android.video",
    defaultName = "org.ungoogled.samsungvideo",
    appName = "Video Player",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 13 permissions + authorities.
    minimumNames = 10,
)
