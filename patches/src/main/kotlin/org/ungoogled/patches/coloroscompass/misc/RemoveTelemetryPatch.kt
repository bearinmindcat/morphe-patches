package org.ungoogled.patches.coloroscompass.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.coloroscompass.ColorOsCompassConstants.COMPATIBILITY_COLOROS_COMPASS

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_COMPASS,
    extension = COLOROS_COMPASS_EXTENSION,
    runtime = "Lorg/ungoogled/coloroscompass/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
