package org.ungoogled.patches.sonylauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SonyLauncherConstants {
    /**
     * Xperia Home (Sony's home screen) is a single APK. Pinned to the version tested: R8 renames its classes every
     * build. It needs Android 15 or later.
     */
    val COMPATIBILITY_XPERIA_HOME = Compatibility(
        name = "Xperia Home",
        packageName = "com.sonymobile.launcher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x26C6DA,
        targets = listOf(
            AppTarget(version = "15"),
        ),
    )
}
