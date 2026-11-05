package org.ungoogled.patches.samsungcalendar.misc

import app.morphe.patcher.Fingerprint

/**
 * The event alert channel's sound: one of One UI's own notification sounds, looked up by title ("Falling Leaf" in
 * 12.8, "Sticky" / "Cricket Bottle" / "On Time" in 12.5); found by its log line, which every version has.
 */
internal object AlertSoundFingerprint : Fingerprint(
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("createNotificationChannel : ringtone = "),
)
