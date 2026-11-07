package org.ungoogled.patches.coloroscamera

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object ColorOsCameraConstants {
    /**
     * OnePlus / OPPO Camera, the system camera of ColorOS and OxygenOS. It only works as itself on the phone it came
     * with: every picture goes through ColorOS's camera unit (a system library plus OPPO's camera service), which only
     * serves the system camera's own package name and signature. A re-signed copy can't be installed over it, and a
     * renamed one gets no camera at all, so the patched APK is only usable as a root mount install over the phone's own
     * camera, which keeps the stock package and signature. A mount needs the exact version installed, and every phone
     * model has its own build, so no version is pinned: the patches find their code by strings and by the processing
     * service's member names, checked on 6.070.215 and 6.020.951 (Android 15+) and 5.020.905 (Android 11+).
     */
    val COMPATIBILITY_COLOROS_CAMERA = Compatibility(
        name = "OnePlus Camera",
        packageName = "com.oplus.camera",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1F2229,
        // A null version: whatever build the phone has.
        targets = listOf(AppTarget(version = null)),
    )
}
