package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/** org.ungoogled.waze.Bridge, the runtime half (extensions/waze). */
private const val BRIDGE = "Lorg/ungoogled/waze/Bridge;"

/**
 * AlerterNativeManager.updateAlertersRepository(NativeAlertRepositoryUpdate): the native side's
 * list of the alerts Waze is about to warn about, each time it changes -- AlerterNativeManager's one
 * method taking that JNI proto (renamed in Waze 5.26, where the JNI half parses the bytes first).
 */
private object UpdateAlertersRepositoryFingerprint : Fingerprint(
    definingClass = "Lcom/waze/alerters/AlerterNativeManager;",
    returnType = "V",
    parameters = listOf("Lcom/waze/jni/protos/alerters/NativeAlertRepositoryUpdate;"),
)

@Suppress("unused")
val wazeShareAlertsPatch = bytecodePatch(
    name = "Share alerts with Maps",
    description = "Shows Waze alerts in Ungoogled Maps on this phone. Waze must be signed in and open.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    // The extension, the Application hook and a switch on Waze's Customization screen.
    dependsOn(wazeCustomizationPatch)

    execute {
        // p0 is the manager, p1 the update; the Bridge takes them as Objects and reads the update by name.
        UpdateAlertersRepositoryFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $BRIDGE->alerts(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
        WazeApplicationCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $BRIDGE->init(Landroid/app/Application;)V",
        )
        declareWazeToggle("shareAlerts")
    }
}

@Suppress("unused")
val wazeStayAwakePatch = bytecodePatch(
    name = "Stay awake in the background",
    description = "Keeps Waze running without a route so its alerts keep reaching Maps. Uses more battery.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    val minutes by intOption(
        key = "minutes",
        default = 180,
        title = "Minutes in the background",
        description = "Waze: 10.",
        required = true,
    )

    execute {
        keepWazeConfig("stayAwake", "CONFIG_VALUE_POWER_BACKGROUND_TIMEOUT_MS", minutes!! * 60_000)
    }
}
