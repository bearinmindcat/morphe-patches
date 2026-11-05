package org.ungoogled.patches.samsungkeyboard

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungKeyboardConstants {
    /**
     * Samsung Keyboard ships inside One UI as a single system APK (/system/app/HoneyBoard), signed by Samsung and
     * sharing the user id android.uid.honeyboard. Pinned to measured versions: 5.9.40.75 from the user's Galaxy Z
     * Flip7, the rest from APKMirror ("Samsung Keyboard", samsung-honey-board). Samsung ships two builds under one
     * version name (versionCode ...300 / ...400); R8 renames classes in every build.
     */
    val COMPATIBILITY_SAMSUNG_KEYBOARD = Compatibility(
        name = "Samsung Keyboard",
        packageName = "com.samsung.android.honeyboard",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3E91FF,
        targets = listOf(
            AppTarget(version = "5.9.40.75"),
            AppTarget(version = "5.9.40.48"),
            AppTarget(version = "5.9.40.12"),
            AppTarget(version = "5.9.40.11"),
            AppTarget(version = "5.9.40.6"),
            AppTarget(version = "5.9.30.97"),
            AppTarget(version = "5.9.30.95"),
            AppTarget(version = "5.9.30.90"),
        ),
    )
}
