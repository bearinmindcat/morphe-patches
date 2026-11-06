package org.ungoogled.patches.gmail

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object GmailConstants {
    /**
     * Gmail from the Play Store: `base.apk` plus ABI, density and language splits. The patches find
     * what they touch by strings, un-obfuscated class names and shapes, since every build renames the
     * obfuscated classes; each listed version has been checked.
     */
    val COMPATIBILITY_GMAIL = Compatibility(
        name = "Gmail",
        packageName = "com.google.android.gm",
        apkFileType = ApkFileType.APKM,
        // The launcher icon's background plate is white.
        appIconColor = 0xFFFFFF,
        targets = listOf(
            AppTarget(version = "2026.11.02.1012472355.Release"),
        ),
    )

    /** org.ungoogled.gmail, the runtime half (extensions/gmail). */
    const val GMAIL_EXTENSION = "extensions/gmail.mpe"
}
