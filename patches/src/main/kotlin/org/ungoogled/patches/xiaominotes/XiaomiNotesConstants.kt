package org.ungoogled.patches.xiaominotes

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object XiaomiNotesConstants {
    /** Xiaomi Notes is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_XIAOMI_NOTES = Compatibility(
        name = "Xiaomi Notes",
        packageName = "com.miui.notes",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFDC038,
        targets = listOf(
            AppTarget(version = "3.2.7.9"),
        ),
    )
}
