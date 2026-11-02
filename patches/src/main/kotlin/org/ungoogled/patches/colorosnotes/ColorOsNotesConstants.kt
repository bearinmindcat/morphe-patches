package org.ungoogled.patches.colorosnotes

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsNotesConstants {
    /** OnePlus Notes is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_COLOROS_NOTES = Compatibility(
        name = "OnePlus Notes",
        packageName = "com.oneplus.note",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF9B80B,
        targets = listOf(
            AppTarget(version = "16.6.14"),
        ),
    )
}
