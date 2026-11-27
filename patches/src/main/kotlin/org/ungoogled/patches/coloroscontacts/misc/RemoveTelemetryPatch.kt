package org.ungoogled.patches.coloroscontacts.misc

import org.ungoogled.patches.coloros.colorOsRemoveTelemetryPatch
import org.ungoogled.patches.coloroscontacts.ColorOsContactsConstants.COMPATIBILITY_COLOROS_CONTACTS

@Suppress("unused")
val removeTelemetryPatch = colorOsRemoveTelemetryPatch(
    compatibility = COMPATIBILITY_COLOROS_CONTACTS,
    extension = COLOROS_CONTACTS_EXTENSION,
    runtime = "Lorg/ungoogled/coloroscontacts/ColorOS;",
    enablePatch = enableNonColorOsPatch,
)
