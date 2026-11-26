package org.ungoogled.patches.samsungphotoeditor.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungphotoeditor.SamsungPhotoEditorConstants.COMPATIBILITY_SAMSUNG_PHOTO_EDITOR

/** Installs the port next to Samsung's own Photo Editor on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_PHOTO_EDITOR,
    stock = "com.sec.android.mimage.photoretouching",
    defaultName = "org.ungoogled.photoretouching",
    appName = "Photo Editor",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 11 (3.4.14.63: 8) permissions + authorities.
    minimumNames = 6,
)
