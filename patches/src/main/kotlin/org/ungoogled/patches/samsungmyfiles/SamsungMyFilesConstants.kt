package org.ungoogled.patches.samsungmyfiles

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object SamsungMyFilesConstants {
    /**
     * My Files is a single APK from the Galaxy Store. Pinned to 15.2.03.2 (15.4 and later need Android 16): R8 renames
     * its classes every build. It asks for Android 14; the patch lets it run on Android 13 too.
     */
    val COMPATIBILITY_SAMSUNG_MY_FILES = Compatibility(
        name = "Samsung My Files",
        packageName = "com.sec.android.app.myfiles",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xF6BC0B,
        targets = listOf(
            AppTarget(version = "15.2.03.2", minSdk = 33, description = "needs Android 13 or newer"),
        ),
    )
}
