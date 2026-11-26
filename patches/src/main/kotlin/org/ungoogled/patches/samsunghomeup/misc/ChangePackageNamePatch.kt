package org.ungoogled.patches.samsunghomeup.misc

import org.ungoogled.patches.samsung.HOME_UP_CLONE
import org.ungoogled.patches.samsung.HOME_UP_PACKAGE
import org.ungoogled.patches.samsung.ONE_UI_HOME_SIBLING
import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsunghomeup.SamsungHomeUpConstants.COMPATIBILITY_SAMSUNG_HOME_UP

/** Installs the port next to Samsung's own Home Up on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_HOME_UP,
    stock = HOME_UP_PACKAGE,
    defaultName = HOME_UP_CLONE,
    appName = "Home Up",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 8 permissions + authorities.
    minimumNames = 6,
    siblings = listOf(ONE_UI_HOME_SIBLING),
    extensionClasses = "Lorg/ungoogled/samsunghomeup/",
    description = "Installs next to Samsung's own Home Up, for the renamed One UI Home.",
)
