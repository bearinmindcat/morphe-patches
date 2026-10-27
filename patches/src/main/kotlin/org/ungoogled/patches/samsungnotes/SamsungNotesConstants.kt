package org.ungoogled.patches.samsungnotes

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungNotesConstants {
    /**
     * Samsung Notes is a single APK from the Galaxy Store. Pinned to 4.4.48.15: R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_NOTES = Compatibility(
        name = "Samsung Notes",
        packageName = "com.samsung.android.app.notes",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xE8412C,
        targets = listOf(
            AppTarget(version = "4.4.48.15"),
        ),
    )
}
