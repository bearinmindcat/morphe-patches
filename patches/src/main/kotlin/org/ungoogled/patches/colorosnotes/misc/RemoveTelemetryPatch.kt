package org.ungoogled.patches.colorosnotes.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.colorosnotes.ColorOsNotesConstants.COMPATIBILITY_COLOROS_NOTES

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_NOTES,
    extension = COLOROS_NOTES_EXTENSION,
    runtime = "Lorg/ungoogled/colorosnotes/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
