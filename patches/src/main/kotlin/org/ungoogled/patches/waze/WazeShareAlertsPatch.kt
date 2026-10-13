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
 * list of the alerts Waze is about to warn about, each time it changes. A JNI class and a JNI
 * proto, so the names survive from one Waze build to the next.
 */
private object UpdateAlertersRepositoryFingerprint : Fingerprint(
    definingClass = "Lcom/waze/alerters/AlerterNativeManager;",
    name = "updateAlertersRepository",
    returnType = "V",
    parameters = listOf("Lcom/waze/jni/protos/alerters/NativeAlertRepositoryUpdate;"),
)


@Suppress("unused")
val wazeShareAlertsPatch = bytecodePatch(
    name = "Share alerts with Maps",
    description = "Hands the alerts Waze is tracking -- police, crashes, hazards, traffic jams, closures and " +
        "speed cameras -- to Ungoogled Maps on the same phone, where Waze alerts in Waze & external data source puts them on " +
        "the map. Nothing leaves the phone. Waze has to be signed in and open; in the background is enough.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    // The extension, the Application hook and a switch on Waze's Customization screen.
    dependsOn(wazeCustomizationPatch)

    execute {
        // p1 is the update; the Bridge takes it as an Object and reads it by name.
        UpdateAlertersRepositoryFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $BRIDGE->alerts(Ljava/lang/Object;)V",
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
    description = "Lets Waze keep running in the background without a route for the chosen time -- Waze's " +
        "server allows 10 minutes -- so that its alerts keep reaching Maps while you navigate there. " +
        "Costs battery; Waze still closes itself when you close it. The time can be changed later in " +
        "Customization, at the bottom of Waze's side menu.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    val minutes by intOption(
        key = "minutes",
        default = 180,
        title = "Minutes in the background",
        description = "How long Waze may run in the background without a route. Waze: 10.",
        required = true,
    )

    execute {
        keepWazeConfig("stayAwake", "CONFIG_VALUE_POWER_BACKGROUND_TIMEOUT_MS", minutes!! * 60_000)
    }
}
