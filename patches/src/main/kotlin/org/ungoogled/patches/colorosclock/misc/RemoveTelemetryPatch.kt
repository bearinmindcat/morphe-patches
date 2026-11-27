package org.ungoogled.patches.colorosclock.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.colorosclock.ColorOsClockConstants.COMPATIBILITY_COLOROS_CLOCK

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_CLOCK,
    extension = COLOROS_CLOCK_EXTENSION,
    runtime = "Lorg/ungoogled/colorosclock/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
