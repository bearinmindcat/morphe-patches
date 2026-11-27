package org.ungoogled.patches.waze

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/** org.ungoogled.waze.SpeedCameras, the runtime half (extensions/waze); shares OsmCameras with Flock cameras. */
private const val SPEED_CAMERAS = "Lorg/ungoogled/waze/SpeedCameras;"

/**
 * Speed, red light and average speed cameras from OpenStreetMap, chimed when one is ahead -- in every country,
 * France included, where Waze's servers send no fixed cameras (issue #87). Warning of cameras is against the law
 * in some countries, so it stays off until switched on in Customization.
 */
@Suppress("unused")
val wazeSpeedCameraAlertsPatch = bytecodePatch(
    name = "Speed camera alerts",
    description = "Chime when a speed camera is ahead, from OpenStreetMap, in any country. No map pins or routing. Off until switched on.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeCustomizationPatch)

    // How loud the chime is (issue #87); Flock cameras chime the same way. Changeable in Customization.
    val chime by stringOption(
        key = "chime",
        default = "normal",
        values = mapOf("Normal" to "normal", "Loud" to "loud", "Loud, at alarm volume" to "alarm"),
        title = "Chime",
        description = "How loud the camera chime is.",
        required = true,
    )

    execute {
        WazeApplicationCreateFingerprint.method.addInstruction(0, "invoke-static { p0 }, $SPEED_CAMERAS->init(Landroid/app/Application;)V")
        declareWazeToggle("speedCameras")
        declareWazeValue("speedCameras", "CAMERA_CHIME", chime!!)
    }
}
