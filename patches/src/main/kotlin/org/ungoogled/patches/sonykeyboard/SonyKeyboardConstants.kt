package org.ungoogled.patches.sonykeyboard

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SonyKeyboardConstants {
    /** Xperia Keyboard (the last build Sony published, 2019) is a single APK. Pinned to the version tested. */
    val COMPATIBILITY_XPERIA_KEYBOARD = Compatibility(
        name = "Xperia Keyboard",
        packageName = "com.sonyericsson.textinput.uxp",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x2E3D4A,
        targets = listOf(
            AppTarget(version = "8.1.A.0.12", minSdk = 19, description = "needs Android 4.4 or newer"),
        ),
    )
}
