package org.ungoogled.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * Google Maps ships as an App Bundle — `base.apk` plus `split_config.<abi>`,
     * `split_config.<density>` and language splits — so the file users need from
     * ApkMirror is an APKM, not a bare APK. The splits are not optional: native
     * code and most resources live in them, not in `base.apk`.
     *
     * Every patch here is pinned to one exact version. The classes these patches
     * target are R8-obfuscated, and R8 renames them on every build, so a patch
     * derived against 26.36.04 cannot be assumed to work on anything else.
     * Fingerprints are written to survive that where possible, but the version
     * is still declared explicitly rather than as `null`.
     */
    val COMPATIBILITY_MAPS = Compatibility(
        name = "Google Maps",
        packageName = "com.google.android.apps.maps",
        apkFileType = ApkFileType.APKM,
        // The launcher icon's background plate is white.
        appIconColor = 0xFFFFFF,
        targets = listOf(
            AppTarget(version = "26.36.04.973607363"),
        ),
    )
}
