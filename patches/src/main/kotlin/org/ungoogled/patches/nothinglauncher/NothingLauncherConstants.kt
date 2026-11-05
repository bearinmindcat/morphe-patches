package org.ungoogled.patches.nothinglauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object NothingLauncherConstants {
    /**
     * Nothing Launcher (com.nothing.launcher) is a split bundle from APKMirror (APKM). Pinned to the version tested:
     * it is R8-obfuscated, so its class names change every build. It needs Android 16 or later.
     */
    val COMPATIBILITY_NOTHING_LAUNCHER = Compatibility(
        name = "Nothing Launcher",
        packageName = "com.nothing.launcher",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xD71921,
        targets = listOf(
            AppTarget(version = "4.0.20"),
        ),
    )
}
