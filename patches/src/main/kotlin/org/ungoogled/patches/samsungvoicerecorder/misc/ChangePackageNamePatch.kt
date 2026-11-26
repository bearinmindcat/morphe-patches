package org.ungoogled.patches.samsungvoicerecorder.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungvoicerecorder.SamsungVoiceRecorderConstants.COMPATIBILITY_SAMSUNG_VOICE_RECORDER

/** Installs the port next to Samsung's own Voice Recorder on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_VOICE_RECORDER,
    stock = "com.sec.android.app.voicenote",
    defaultName = "org.ungoogled.voicenote",
    appName = "Voice Recorder",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 23 permissions + authorities.
    minimumNames = 17,
)
