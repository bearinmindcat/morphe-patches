package org.ungoogled.patches.samsungemail.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungemail.SamsungEmailConstants.COMPATIBILITY_SAMSUNG_EMAIL

/** Installs the port next to Samsung's own Email on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_EMAIL,
    stock = "com.samsung.android.email.provider",
    defaultName = "org.ungoogled.samsungemail",
    appName = "Email",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 36 permissions + authorities.
    minimumNames = 27,
)
