package org.ungoogled.patches.waze

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE
import org.w3c.dom.Element

/**
 * Permissions driving does not need. Waze asks for each at the moment a feature needs it, and treats one it
 * no longer declares the same as one the user denied (issue #67).
 *
 * Kept on purpose: location, background location included (drive detection, Stay awake in the background and
 * the alerts Waze shares with Maps), the microphone (hands-free "OK Waze" voice commands and voice reports),
 * push messages (drive reminders), Bluetooth and audio routing, sync, notifications, Android Auto.
 */
private val PERMISSIONS = listOf(
    "android.permission.READ_CONTACTS",              // sharing a drive with contacts
    "android.permission.READ_PROFILE",               // your own contact card
    "android.permission.READ_CALENDAR",              // drives suggested from calendar events
    "android.permission.READ_PHONE_STATE",           // call state; Android hands audio to a call anyway
    "android.permission.CAMERA",                     // photos on reports and the profile picture
    "com.google.android.gms.permission.AD_ID",       // advertising ID
)

@Suppress("unused")
val wazeRemovePermissionsPatch = resourcePatch(
    name = "Remove permissions",
    description = "Removes contacts, calendar, phone, camera and advertising ID access.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_WAZE)

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
