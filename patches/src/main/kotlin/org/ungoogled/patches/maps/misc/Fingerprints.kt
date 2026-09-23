package org.ungoogled.patches.maps.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

/** Host of every Firebase Installations request URL. */
internal const val FIREBASE_INSTALLATIONS_HOST = "firebaseinstallations.googleapis.com"

/** Host the Play services compliance gRPC channel is pointed at. */
internal const val GMS_COMPLIANCE_HOST = "gmscompliance-pa.googleapis.com"

/**
 * The single helper that formats every Firebase Installations request URL.
 * The host literal occurs exactly once in the whole APK, so the string is both
 * the anchor and the instruction to rewrite.
 */
internal object FirebaseInstallationsHostFingerprint : Fingerprint(
    filters = listOf(string(FIREBASE_INSTALLATIONS_HOST)),
)

/**
 * The static initialiser that configures the compliance gRPC channel. Same
 * story: one occurrence, and it is the instruction we want.
 */
internal object GmsComplianceHostFingerprint : Fingerprint(
    filters = listOf(string(GMS_COMPLIANCE_HOST)),
)

/**
 * One of the two private senders that every ad impression, click and
 * "ved"-tagged ping funnels into. Seven methods in the app call
 * URLUtil.isNetworkUrl; only these two are private void methods, and they sit
 * in the same class, so the patch finds the class through this and then takes
 * both senders from it.
 */
internal object AdPingSenderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(methodCall(definingClass = "Landroid/webkit/URLUtil;", name = "isNetworkUrl")),
)

/**
 * The promoted-content impression sender: turns the server's "/adview?avs=..."
 * URL into an absolute https://www.google.com one and hands it to the HTTP
 * client. Its callers (sponsored search rows, promoted hotels, ...) only track
 * what they have already pinged. Its parameter type is one of Maps' few
 * unobfuscated classes.
 */
internal object AdViewImpressionFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lcom/google/android/apps/gmm/util/clearcut/metrics/Metrics\$IntegerMetric;"),
    filters = listOf(string("HTTPS"), string("www.google.com")),
)
