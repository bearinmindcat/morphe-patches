package org.ungoogled.patches.maps.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

/**
 * Permissions that only serve Google-account features or Google's own data
 * collection. Maps asks for each at the moment a feature needs it, and treats a
 * permission it no longer declares the same as one the user denied.
 */
private val PERMISSIONS = listOf(
    "android.permission.ACCESS_BACKGROUND_LOCATION",           // Location History, location sharing
    "android.permission.ACTIVITY_RECOGNITION",                 // Timeline's walk/drive detection
    "com.google.android.gms.permission.ACTIVITY_RECOGNITION",
    "android.permission.READ_CONTACTS",                        // sharing with contacts
    "android.permission.RECORD_AUDIO",                         // voice search
    "android.permission.CAMERA",                               // Lens, Live View
    "com.google.android.gms.permission.CAR_SPEED",             // Android Auto speed reporting
    "com.google.android.gms.permission.AD_ID",                 // advertising ID
    "com.google.android.c2dm.permission.RECEIVE",              // push messages
    "com.google.android.providers.gsf.permission.READ_GSERVICES",
)

@Suppress("unused")
val removePermissionsPatch = resourcePatch(
    name = "Remove permissions",
    description = "Removes permissions that only serve Google-account features or Google's data " +
        "collection: background location, physical activity, contacts, microphone (voice search stops " +
        "working), camera (Lens and Live View stop working), car speed, advertising ID, push messages " +
        "and Google services settings.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        var removed = 0
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val children = root.childNodes
            val doomed = (0 until children.length).mapNotNull { children.item(it) as? Element }.filter {
                it.tagName.startsWith("uses-permission") && it.getAttribute("android:name") in PERMISSIONS
            }
            for (element in doomed) {
                root.removeChild(element)
                removed++
            }
        }
        if (removed != PERMISSIONS.size) {
            throw PatchException("expected to remove ${PERMISSIONS.size} permissions, found $removed")
        }
    }
}
