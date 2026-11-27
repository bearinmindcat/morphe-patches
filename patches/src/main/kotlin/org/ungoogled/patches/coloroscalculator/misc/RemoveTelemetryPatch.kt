package org.ungoogled.patches.coloroscalculator.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.coloroscalculator.ColorOsCalculatorConstants.COMPATIBILITY_COLOROS_CALCULATOR

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_CALCULATOR,
    extension = COLOROS_CALCULATOR_EXTENSION,
    runtime = "Lorg/ungoogled/coloroscalculator/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
