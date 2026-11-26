package org.ungoogled.patches.samsungnotes.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungnotes.SamsungNotesConstants.COMPATIBILITY_SAMSUNG_NOTES

/** Installs the port next to Samsung's own Notes on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_NOTES,
    stock = "com.samsung.android.app.notes",
    defaultName = "org.ungoogled.samsungnotes",
    appName = "Notes",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 28 permissions + authorities.
    minimumNames = 21,
)
