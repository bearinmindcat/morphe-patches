package org.ungoogled.patches.samsungsoundassistant.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** Samsung Analytics' "may the tracker send?" check, read before it starts and before every log it sends. */
internal object AnalyticsTrackerReadyFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string("Tracker is not initialized, status : ")),
)
