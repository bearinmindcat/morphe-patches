package org.ungoogled.patches.honornotes

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object HonorNotesConstants {
    /** Honor Notes is a single APK. Pinned to the version tested: R8 renames its classes every build. */
    val COMPATIBILITY_HONOR_NOTES = Compatibility(
        name = "Honor Notes",
        packageName = "com.hihonor.notepad",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF5B800,
        targets = listOf(
            AppTarget(version = "18.0.4.817"),
        ),
    )
}
