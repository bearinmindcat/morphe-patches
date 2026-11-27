package org.ungoogled.patches.colorosweather.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.colorosweather.ColorOsWeatherConstants.COMPATIBILITY_COLOROS_WEATHER

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_WEATHER,
    extension = COLOROS_WEATHER_EXTENSION,
    runtime = "Lorg/ungoogled/colorosweather/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
