package org.ungoogled.patches.samsungkeyboard

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungKeyboardConstants {
    /**
     * Samsung Keyboard ships inside One UI as a single system APK (/system/app/HoneyBoard), signed by Samsung and
     * sharing the user id android.uid.honeyboard. Pinned to the version pulled from the user's Galaxy Z Flip7:
     * R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_KEYBOARD = Compatibility(
        name = "Samsung Keyboard",
        packageName = "com.samsung.android.honeyboard",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x3E91FF,
        targets = listOf(
            AppTarget(version = "5.9.40.75"),
        ),
    )
}
