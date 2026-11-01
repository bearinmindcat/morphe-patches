package org.ungoogled.patches.xiaomigallery.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

internal const val INTENT_UTIL = "Lcom/miui/gallery/util/IntentUtil;"

/** The button on Gallery's "Permissions required" page (StorageGuideFragment), Global build first. */
internal object StorageGuideButtonFingerprint : Fingerprint(
    definingClass = "Lcom/miui/gallery/ui/StorageGuideFragment;",
    returnType = "V",
    parameters = listOf("Z", "Landroid/view/View;"),
    filters = listOf(
        methodCall(definingClass = INTENT_UTIL, name = "enterGlobalStoragePermission"),
        methodCall(definingClass = INTENT_UTIL, name = "enterManageExternalStoragePermission"),
    ),
)
