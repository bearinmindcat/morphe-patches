package org.ungoogled.patches.samsungmusic.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** "Sound quality and effects": opens SoundAlive's settings, or does nothing when SoundAlive is not installed. */
internal object SoundAliveLaunchFingerprint : Fingerprint(
    returnType = "V",
    // In code order: the panel fallback, then the "no action" branch at the end.
    filters = listOf(string("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL"), string("launch() but no action.")),
)

/** The media browser's caller check: a known package signed with one of Samsung's or Google's keys. */
internal object MediaBrowserCallerFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    filters = listOf(string("This request is not valid name")),
)
