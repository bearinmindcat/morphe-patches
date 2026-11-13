package org.ungoogled.patches.samsungfinder

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungFinderConstants {
    /**
     * Finder is a single APK from the Galaxy Store. Pinned to 10.2.13.0, the newest with its own search screen (11.x
     * is an empty shell: One UI 6 moved the search into One UI Home): R8 renames its classes every build.
     */
    val COMPATIBILITY_SAMSUNG_FINDER = Compatibility(
        name = "Samsung Finder",
        packageName = "com.samsung.android.app.galaxyfinder",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x0088AA,
        targets = listOf(
            AppTarget(version = "10.2.13.0", minSdk = 29, description = "needs Android 10 or newer"),
        ),
    )
}
