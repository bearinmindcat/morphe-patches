package org.ungoogled.patches.colorosgallery.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.colorosgallery.ColorOsGalleryConstants.COMPATIBILITY_COLOROS_GALLERY

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_GALLERY,
    extension = COLOROS_GALLERY_EXTENSION,
    runtime = "Lorg/ungoogled/colorosgallery/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
