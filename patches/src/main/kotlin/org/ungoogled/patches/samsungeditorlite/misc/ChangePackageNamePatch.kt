package org.ungoogled.patches.samsungeditorlite.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungeditorlite.SamsungEditorLiteConstants.COMPATIBILITY_SAMSUNG_EDITOR_LITE

/** Installs the port next to Samsung's own Editor Lite on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_EDITOR_LITE,
    stock = "com.samsung.app.newtrim",
    defaultName = "org.ungoogled.newtrim",
    appName = "Editor Lite",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 7 permissions + authorities.
    minimumNames = 5,
)
