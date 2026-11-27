package org.ungoogled.patches.coloroslauncher.misc

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.coloros.dropOplusTrackEvents
import org.ungoogled.patches.coloroslauncher.ColorOsLauncherConstants.COMPATIBILITY_COLOROS_LAUNCHER
import org.w3c.dom.Element

private const val LOG_UPLOAD_RECEIVER = "com.oplus.utrace.hlog.HLogReceiver"

/**
 * 15.6.24 declares UTrace's HLogReceiver (action com.oplus.pantanal.log.upload): on ColorOS the system's log kit
 * asks the launcher through it to upload its logs to OPPO. The receiver goes; 16.4.30 has none.
 */
private val logUploadReceiverPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val receivers = manifest.getElementsByTagName("receiver")
            (0 until receivers.length).map { receivers.item(it) as Element }
                .filter { it.getAttribute("android:name") == LOG_UPLOAD_RECEIVER }
                .forEach { it.parentNode.removeChild(it) }
        }
    }
}

@Suppress("unused")
val removeTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "Stops usage statistics and log uploads to OPPO on OPPO, OnePlus and realme phones.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_LAUNCHER)
    dependsOn(enableNonColorOsPatch, logUploadReceiverPatch)

    execute {
        dropOplusTrackEvents()
    }
}
