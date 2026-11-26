package org.ungoogled.patches.samsungmyfiles.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungmyfiles.SamsungMyFilesConstants.COMPATIBILITY_SAMSUNG_MY_FILES

/** Installs the port next to Samsung's own My Files on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_MY_FILES,
    stock = "com.sec.android.app.myfiles",
    defaultName = "org.ungoogled.myfiles",
    appName = "My Files",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 17 permissions + authorities.
    minimumNames = 12,
)
