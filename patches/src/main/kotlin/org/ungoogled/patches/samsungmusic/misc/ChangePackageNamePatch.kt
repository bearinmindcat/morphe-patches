package org.ungoogled.patches.samsungmusic.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungmusic.SamsungMusicConstants.COMPATIBILITY_SAMSUNG_MUSIC

/** Installs the port next to Samsung's own Music on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_MUSIC,
    stock = "com.sec.android.app.music",
    defaultName = "org.ungoogled.samsungmusic",
    appName = "Music",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 29 permissions + authorities.
    minimumNames = 22,
)
