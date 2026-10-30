package org.ungoogled.patches.samsunggallery.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** Samsung Analytics' "may the tracker send?" check, read before it starts and before every log it sends. */
internal object AnalyticsTrackerReadyFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string("Tracker is not initialized, status : ")),
)

/** Gallery's handler for the policies Samsung's policy service (SCPM) pushes to it. */
internal object ScpmPolicyReceiverFingerprint : Fingerprint(
    name = "onReceive",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
    filters = listOf(string("com.samsung.android.scpm.policy.UPDATE.")),
)

/** Gallery's registration with Samsung's policy service at start-up, made only when the service is there. */
internal object ScpmRegistrationFingerprint : Fingerprint(
    filters = listOf(string("buildConfig#register failed")),
)
