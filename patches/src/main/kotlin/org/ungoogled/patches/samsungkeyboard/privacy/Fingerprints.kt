package org.ungoogled.patches.samsungkeyboard.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** Samsung Analytics' "may the tracker send?" check, read before it starts and before every log it sends. */
internal object AnalyticsTrackerReadyFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string("Tracker is not initialized, status : ")),
)

/** The DiagMon SDK's version of Samsung's diagnostics agent; 0 when none is installed. */
internal object DiagMonAgentVersionFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("DMA Client is not exist")),
)


/** Galaxy AI's consent upload (serial number, model, OS) to Samsung's consent server: "can sync now?". */
internal object ConsentSyncFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(string("canSyncNow=false isSamsungAccountLoggedIn=")),
)

/** Hands the text of a search box to Samsung's on-device data collector (Moneta). */
internal object TypedTextCollectionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I", "Ljava/lang/String;"),
    filters = listOf(string("[[AiWriter]_DATA_COLLECTION]")),
)

/** Sends GIPHY's queued view and share sessions ("pingbacks"). */
internal object GiphyPingbackFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("api_key"), string("pingback_id")),
)

/** Sends Tenor's registerview / registershare / registeraction URL. */
internal object TenorRegisterFingerprint : Fingerprint(
    name = "doInBackground",
    filters = listOf(string("url is null")),
)
