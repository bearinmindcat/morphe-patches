package org.ungoogled.patches.samsungclock.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** The bundled IDreamManager proxy's isDreaming(), which needs a system permission (READ_DREAM_STATE). */
internal object IsDreamingFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(string("android.service.dreams.IDreamManager")),
)

/** The ringing alarm's notification, whose tap opens the alarm screen. */
internal object AlarmRingingNotificationFingerprint : Fingerprint(
    returnType = "Landroid/app/Notification;",
    parameters = listOf("Landroid/content/Context;", "Lcom/sec/android/app/clockpackage/alarm/model/AlarmItem;"),
    filters = listOf(string("fromNotification")),
)

/** The ringing timer's notification, whose tap goes through the timer service. */
internal object TimerRingingNotificationFingerprint : Fingerprint(
    returnType = "Landroid/app/Notification;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "J", "I"),
    filters = listOf(string("com.sec.android.app.clockpackage.timer.service.TimerService")),
)
