package org.ungoogled.patches.samsungscreenrecorder.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungscreenrecorder.SamsungScreenRecorderConstants.COMPATIBILITY_SAMSUNG_SCREEN_RECORDER

/** Installs the port next to Samsung's own Screen Recorder on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_SCREEN_RECORDER,
    stock = "com.sec.app.screenrecorder",
    defaultName = "org.ungoogled.samsungscreenrecorder",
    appName = "Screen Recorder",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 1 permissions + authorities.
    minimumNames = 1,
)
