package org.ungoogled.patches.samsungvideoplayer.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** Video Player's feature table: One UI version and floating-feature answers, read once at start-up. */
internal object FeatureTableFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(string("SEC_FLOATING_FEATURE_LCD_CONFIG_HFR_MODE"), string("SEC_FLOATING_FEATURE_COMMON_SUPPORT_PTT")),
)

/** The pop-up player button: Android's picture-in-picture, or Samsung's own floating player. */
internal object PopUpButtonFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("To prevent malfunction switching to PopupPlayer in edge of playing.")),
)

/** FileInfoUtil.setPlayingFileInfo: turns a storage document URI into the path the player plays (off One UI). */
internal object PlayingFileInfoFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/net/Uri;"),
    filters = listOf(string("setPlayingFileInfo. EXTERNAL_STORAGE case mFilePath converted to sdpPath")),
)

/** SemMediaPlayerControlImpl's init: the player opens the path it is given, or the URI when it is a content:// one. */
internal object PlayerInitFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Z", "Ljava/lang/String;", "Landroid/net/Uri;"),
    filters = listOf(string("SemMediaPlayer init async. mVideoWidth: ")),
)
