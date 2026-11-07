package org.ungoogled.patches.colorosdocuments

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsDocumentsConstants {
    /**
     * OnePlus Documents is a single APK. Pinned to the version tested: R8 renames its classes every build. APKMirror has
     * two 17.1.26 uploads with the same version code (the international build and a China build); both are covered.
     */
    val COMPATIBILITY_COLOROS_DOCUMENTS = Compatibility(
        name = "OnePlus Documents",
        packageName = "andes.oplus.documentsreader",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x26B0F4,
        targets = listOf(
            AppTarget(version = "17.1.26", minSdk = 35, description = "needs Android 15 or newer"),
        ),
    )
}
