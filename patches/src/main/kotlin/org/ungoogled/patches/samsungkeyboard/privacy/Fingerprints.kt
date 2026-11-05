package org.ungoogled.patches.samsungkeyboard.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/*
 * Samsung ships different builds under one version name (versionCode ...300 and ...400 builds of 5.9.40.x differ in how
 * R8 laid them out: inlined or not, static or instance, other parameters), so these match by the strings each piece logs
 * or sends, and by return type only where every build agrees.
 */

/** Samsung Analytics' "may the tracker send?" check, read before it starts and before every log it sends. */
internal object AnalyticsTrackerReadyFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string("Tracker is not initialized, status : ")),
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
    strings = listOf("[[AiWriter]_DATA_COLLECTION]", "send data : "),
)

/** Sends GIPHY's view and share sessions ("pingbacks"): the queue's sender, or the per-session request where R8 kept it. */
internal object GiphyPingbackFingerprint : Fingerprint(
    strings = listOf("api_key", "pingback_id"),
)

/** Opens Tenor's registerview / registershare / registeraction URL (the AsyncTask's background work). */
internal object TenorRegisterFingerprint : Fingerprint(
    strings = listOf("GRS_TenorRegisterManager", "url is null"),
)
