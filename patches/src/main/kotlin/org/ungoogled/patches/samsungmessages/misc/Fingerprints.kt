package org.ungoogled.patches.samsungmessages.misc

import app.morphe.patcher.Fingerprint

private const val DEVICE_UTIL = "Lcom/samsung/android/messaging/common/util/DeviceUtil;"

/** Asks the hidden device idle controller whether Messages is exempt from battery optimisation. */
internal object PowerSaveAllowListedFingerprint : Fingerprint(
    definingClass = DEVICE_UTIL,
    name = "isPowerSaveAllowListed",
    returnType = "Z",
    parameters = listOf(),
)

/** Adds Messages to the battery optimisation exemptions through the hidden device idle controller. */
internal object AddToPowerSaveAllowlistFingerprint : Fingerprint(
    definingClass = DEVICE_UTIL,
    name = "addToPowerSaveAllowlistApp",
    returnType = "V",
    parameters = listOf(),
)

/** Starts one of Messages' work services; off One UI (no Samsung framework) it starts them as foreground services. */
internal object RequestServiceFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/messaging/common/service/ServiceCompat;",
    name = "requestService",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;", "I"),
)

/**
 * Messages' SMS send wrappers: (manager, destination, service centre, parts, sent intents, delivery intents, ...). Off
 * One UI (no Samsung framework) they do nothing at all.
 */
internal object SendMultipartFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/messaging/sepwrapper/SmsManagerWrapper;",
    name = "sendMultipartTextMessage",
    returnType = "V",
    parameters = listOf(
        "Landroid/telephony/SmsManager;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/ArrayList;",
        "Ljava/util/ArrayList;", "Ljava/util/ArrayList;", "Z", "I", "I", "I",
    ),
)

internal object SendMultipartWithValidityFingerprint : Fingerprint(
    definingClass = "Lcom/samsung/android/messaging/sepwrapper/SmsManagerWrapper;",
    name = "sendMultipartTextMessage",
    returnType = "V",
    parameters = listOf(
        "Landroid/telephony/SmsManager;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/ArrayList;",
        "Ljava/util/ArrayList;", "Ljava/util/ArrayList;", "Z", "I", "I", "I", "I",
    ),
)
