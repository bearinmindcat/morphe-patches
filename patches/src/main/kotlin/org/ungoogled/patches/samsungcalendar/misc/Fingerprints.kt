package org.ungoogled.patches.samsungcalendar.misc

import app.morphe.patcher.Fingerprint

/** The event alert channel's sound: one of One UI's own notification sounds, looked up by title. */
internal object AlertSoundFingerprint : Fingerprint(
    returnType = "Landroid/net/Uri;",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("Falling Leaf", "createNotificationChannel : ringtone = "),
)
