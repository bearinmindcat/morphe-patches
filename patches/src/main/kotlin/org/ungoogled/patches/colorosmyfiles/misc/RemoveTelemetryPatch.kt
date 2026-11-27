package org.ungoogled.patches.colorosmyfiles.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.colorosmyfiles.ColorOsMyFilesConstants.COMPATIBILITY_COLOROS_MY_FILES

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_MY_FILES,
    extension = COLOROS_MY_FILES_EXTENSION,
    runtime = "Lorg/ungoogled/colorosmyfiles/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
