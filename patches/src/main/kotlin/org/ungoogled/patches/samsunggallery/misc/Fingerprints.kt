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

/**
 * The SUPPORT_TRASH constant of Gallery's Features (an enum constant with its own getEnabling(), so a class of its
 * own): the Recycle bin, on from One UI's first version (SEM P) up, so never off One UI.
 */
internal object SupportTrashFeatureFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf(),
    filters = listOf(string("SUPPORT_TRASH")),
    custom = { _, classDef -> classDef.superclass == "Lcom/samsung/android/gallery/support/utils/Features;" },
)

/**
 * The SUPPORT_SEC_MP_ORIENTATION_TAG constant of Gallery's Features: whether Samsung's media provider gives each photo's
 * EXIF orientation value, which Gallery needs to show mirrored photos mirrored. On only with Samsung's provider.
 */
internal object OrientationTagFeatureFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf(),
    filters = listOf(string("SUPPORT_SEC_MP_ORIENTATION_TAG")),
    custom = { _, classDef -> classDef.superclass == "Lcom/samsung/android/gallery/support/utils/Features;" },
)
