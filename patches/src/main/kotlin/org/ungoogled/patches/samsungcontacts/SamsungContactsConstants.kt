package org.ungoogled.patches.samsungcontacts

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungContactsConstants {
    /**
     * Samsung Contacts is a single APK from the Galaxy Store. Pinned to 16.3.00.87: R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_CONTACTS = Compatibility(
        name = "Samsung Contacts",
        packageName = "com.samsung.android.app.contacts",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF4545,
        targets = listOf(
            AppTarget(version = "16.3.00.87"),
        ),
    )
}
