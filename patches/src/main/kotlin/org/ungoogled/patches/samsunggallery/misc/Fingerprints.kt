package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

/**
 * FilesApi.getSecFileId: Samsung's file id for a MediaStore id, asked of MediaStore with Samsung's own column
 * ("media_id="). The viewer calls it to find the photo it shows again after the media changed.
 */
internal object SecFileIdFingerprint : Fingerprint(
    returnType = "J",
    parameters = listOf("J"),
    filters = listOf(
        string("media_id="),
        methodCall(definingClass = "Landroid/content/ContentResolver;", name = "query"),
    ),
)
