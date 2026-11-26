package org.ungoogled.patches.samsungsoundassistant.misc

import org.ungoogled.patches.samsung.samsungChangePackageNamePatch
import org.ungoogled.patches.samsungsoundassistant.SamsungSoundAssistantConstants.COMPATIBILITY_SAMSUNG_SOUND_ASSISTANT

/** Installs the port next to Samsung's own Sound Assistant on a Galaxy phone (issue #212); see samsungChangePackageNamePatch. */
@Suppress("unused")
val changePackageNamePatch = samsungChangePackageNamePatch(
    compatibility = COMPATIBILITY_SAMSUNG_SOUND_ASSISTANT,
    stock = "com.samsung.android.soundassistant",
    defaultName = "org.ungoogled.soundassistant",
    appName = "Sound Assistant",
    dependencies = listOf(enableNonOneUiPatch),
    // Measured: 6 permissions + authorities.
    minimumNames = 4,
)
