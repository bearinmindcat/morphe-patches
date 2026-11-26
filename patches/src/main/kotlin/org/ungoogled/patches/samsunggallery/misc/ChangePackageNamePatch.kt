package org.ungoogled.patches.samsunggallery.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsunggallery.SamsungGalleryConstants.COMPATIBILITY_SAMSUNG_GALLERY

/** Installs the port next to Samsung's own Gallery on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_GALLERY,
    stock = "com.sec.android.gallery3d",
    defaultName = "org.ungoogled.samsunggallery",
    appName = "Gallery",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 14 permissions + authorities.
    minimumNames = 10,
)
