package org.ungoogled.patches.colorosdocuments.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.colorosdocuments.ColorOsDocumentsConstants.COMPATIBILITY_COLOROS_DOCUMENTS

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_DOCUMENTS,
    extension = COLOROS_DOCUMENTS_EXTENSION,
    runtime = "Lorg/ungoogled/colorosdocuments/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
