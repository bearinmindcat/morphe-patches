package org.ungoogled.patches.samsungsoundassistant.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/** The main screen's onResume, which sets every row's state (switches, summaries, the links underneath). */
internal object MainScreenResumeFingerprint : Fingerprint(
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("pref_key_volumestar"), string("pref_key_media_volume"), string("SATP100")),
)

/** Adds one link under the main screen ("Separate app sound", "Sound quality and effects"). */
internal object RelativeLinkAddFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Intent;", "Ljava/lang/String;"),
    filters = listOf(
        methodCall(definingClass = "Landroid/view/View;", name = "setTextDirection"),
        methodCall(definingClass = "Landroid/view/ViewGroup;", name = "addView"),
    ),
)
