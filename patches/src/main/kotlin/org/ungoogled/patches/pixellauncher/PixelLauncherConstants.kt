package org.ungoogled.patches.pixellauncher

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object PixelLauncherConstants {
    /**
     * Pixel Launcher, the Pixel home screen. It is a privileged system app tied to the Android build it ships with
     * (recents, gestures and the taskbar run inside it), so the patched APK is only usable as a root (mount) install
     * over the phone's own Pixel Launcher, which keeps the stock package, signature and manifest. Every build of one
     * Android version is versionName "16" or "17" (versionCode 907), with names moved around between monthly builds,
     * so no version is pinned: the patches find their code by class and member names and strings, checked on the
     * Android 16 and 17 builds.
     */
    val COMPATIBILITY_PIXEL_LAUNCHER = Compatibility(
        name = "Pixel Launcher",
        packageName = "com.google.android.apps.nexuslauncher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x4285F4,
        // A null version: whatever build the phone has.
        targets = listOf(AppTarget(version = null)),
    )
}
