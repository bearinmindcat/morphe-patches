package org.ungoogled.patches.colorosscreenrecorder.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.colorosscreenrecorder.ColorOsScreenRecorderConstants.COMPATIBILITY_COLOROS_SCREEN_RECORDER

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_SCREEN_RECORDER,
    extension = COLOROS_SCREEN_RECORDER_EXTENSION,
    runtime = "Lorg/ungoogled/colorosscreenrecorder/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
