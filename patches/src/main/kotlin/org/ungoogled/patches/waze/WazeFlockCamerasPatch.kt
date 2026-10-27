package org.ungoogled.patches.waze

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/** org.ungoogled.waze.Flock, the runtime half (extensions/waze). */
private const val FLOCK = "Lorg/ungoogled/waze/Flock;"

@Suppress("unused")
val wazeFlockCamerasPatch = bytecodePatch(
    name = "Flock cameras",
    description = "Chime when a Flock camera is ahead, from OpenStreetMap. No map pins or routing. Off until switched on.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeCustomizationPatch)

    execute {
        WazeApplicationCreateFingerprint.method.addInstruction(0, "invoke-static { p0 }, $FLOCK->init(Landroid/app/Application;)V")
        declareWazeToggle("flockCameras")
    }
}
