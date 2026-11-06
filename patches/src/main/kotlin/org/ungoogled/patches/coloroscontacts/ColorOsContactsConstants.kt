package org.ungoogled.patches.coloroscontacts

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsContactsConstants {
    /**
     * OnePlus Contacts (ColorOS 16's contacts and dial pad in one app) is a single APK. Pinned to the version tested:
     * R8 renames its classes every build.
     */
    val COMPATIBILITY_COLOROS_CONTACTS = Compatibility(
        name = "OnePlus Contacts",
        packageName = "com.android.contacts",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x2A9D5C,
        targets = listOf(
            AppTarget(version = "16.80.15"),
        ),
    )
}
