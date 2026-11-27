package org.ungoogled.patches.colorosrecorder.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.colorosrecorder.ColorOsRecorderConstants.COMPATIBILITY_COLOROS_RECORDER

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_RECORDER,
    extension = COLOROS_RECORDER_EXTENSION,
    runtime = "Lorg/ungoogled/colorosrecorder/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
