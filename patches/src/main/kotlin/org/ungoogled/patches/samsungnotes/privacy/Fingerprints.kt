package org.ungoogled.patches.samsungnotes.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** Samsung Analytics' "may the tracker send?" check, read before it starts and before every log it sends. */
internal object AnalyticsTrackerReadyFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string("Tracker is not initialized, status : ")),
)

/**
 * Samsung Marketing Platform's (SMP) upload of its "clients" data -- device, app usage per screen, marketing consent
 * and marketing feedback -- which first asks whether SMP runs as plain push only.
 */
internal object SmpClientsUploadFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("device does not upload clients data")),
)
