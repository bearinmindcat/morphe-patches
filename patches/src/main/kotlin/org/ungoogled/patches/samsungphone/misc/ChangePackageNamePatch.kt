package org.ungoogled.patches.samsungphone.misc

import org.ungoogled.patches.samsung.SAMSUNG_CONTACTS_SIBLING
import org.ungoogled.patches.samsung.SAMSUNG_PHONE_CLONE
import org.ungoogled.patches.samsung.SAMSUNG_PHONE_PACKAGE
import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungphone.SamsungPhoneConstants.COMPATIBILITY_SAMSUNG_PHONE

/** Installs the port next to Samsung's own Phone on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_PHONE,
    stock = SAMSUNG_PHONE_PACKAGE,
    defaultName = SAMSUNG_PHONE_CLONE,
    appName = "Phone",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 13 permissions + authorities.
    minimumNames = 10,
    siblings = listOf(SAMSUNG_CONTACTS_SIBLING),
)
