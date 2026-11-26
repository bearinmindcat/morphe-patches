package org.ungoogled.patches.samsunglauncher.misc

import org.ungoogled.patches.samsung.HOME_UP_SIBLING
import org.ungoogled.patches.samsung.ONE_UI_HOME_CLONE
import org.ungoogled.patches.samsung.ONE_UI_HOME_PACKAGE
import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsunglauncher.SamsungLauncherConstants.COMPATIBILITY_SAMSUNG_LAUNCHER

/** Installs the port next to Samsung's own One UI Home on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_LAUNCHER,
    stock = ONE_UI_HOME_PACKAGE,
    defaultName = ONE_UI_HOME_CLONE,
    appName = "One UI Home",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 44 (16.0.09.35: 48) permissions + authorities.
    minimumNames = 30,
    siblings = listOf(HOME_UP_SIBLING),
)
