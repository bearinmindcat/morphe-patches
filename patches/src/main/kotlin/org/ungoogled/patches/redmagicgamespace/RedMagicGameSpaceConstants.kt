package org.ungoogled.patches.redmagicgamespace

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object RedMagicGameSpaceConstants {
    /**
     * RedMagic Game Space is a single APK, a system app of RedMagic OS 10 (Android 15), taken from RedMagic 9 Pro's
     * global firmware (REDMAGICOS10.0.7MR1_GB). Pinned to the version tested.
     */
    val COMPATIBILITY_REDMAGIC_GAME_SPACE = Compatibility(
        name = "RedMagic Game Space",
        packageName = "cn.nubia.gamelauncher",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xE60012,
        targets = listOf(
            AppTarget(version = "15.0.000.001.2512060958"),
        ),
    )
}
