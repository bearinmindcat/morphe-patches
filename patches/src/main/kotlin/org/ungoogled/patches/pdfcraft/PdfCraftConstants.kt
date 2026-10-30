package org.ungoogled.patches.pdfcraft

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object PdfCraftConstants {
    /**
     * PdfCraft (artcraft-android/pdfcraft-android). Its patches only change resources and the manifest -- the launcher
     * icon and label -- so they are not pinned to one version.
     */
    val COMPATIBILITY_PDFCRAFT = Compatibility(
        name = "PdfCraft",
        packageName = "io.github.artcraftandroid.pdfcraft",
        apkFileType = ApkFileType.APK,
        appIconColor = 0x12A58A,
        // A null version: any PdfCraft version.
        targets = listOf(AppTarget(version = null)),
    )
}
