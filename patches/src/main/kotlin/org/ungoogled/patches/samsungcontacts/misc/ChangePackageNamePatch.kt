package org.ungoogled.patches.samsungcontacts.misc

import org.ungoogled.patches.samsung.SAMSUNG_CONTACTS_CLONE
import org.ungoogled.patches.samsung.SAMSUNG_CONTACTS_PACKAGE
import org.ungoogled.patches.samsung.SAMSUNG_PHONE_SIBLING
import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungcontacts.SamsungContactsConstants.COMPATIBILITY_SAMSUNG_CONTACTS

/** Installs the port next to Samsung's own Contacts on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_CONTACTS,
    stock = SAMSUNG_CONTACTS_PACKAGE,
    defaultName = SAMSUNG_CONTACTS_CLONE,
    appName = "Contacts",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 32 permissions + authorities.
    minimumNames = 24,
    siblings = listOf(SAMSUNG_PHONE_SIBLING),
)
