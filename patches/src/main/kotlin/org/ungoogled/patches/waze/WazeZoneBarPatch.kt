package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/** org.ungoogled.waze.ZoneBar, the runtime half (extensions/waze). */
private const val ZONE_BAR = "Lorg/ungoogled/waze/ZoneBar;"

/** Waze's older zone display: the share of an average speed zone driven and the metres left. A JNI class. */
private object AverageSpeedZoneUpdateFingerprint : Fingerprint(
    definingClass = "Lcom/waze/navigate/NavigationInfoNativeManager;",
    name = "onAverageSpeedCamZoneUpdate",
    returnType = "V",
    parameters = listOf("Lcom/waze/jni/protos/navigate/AverageSpeedCameraZoneUpdate;"),
)

/** ... and the zone's end. */
private object AverageSpeedZoneClearFingerprint : Fingerprint(
    definingClass = "Lcom/waze/navigate/NavigationInfoNativeManager;",
    name = "onAverageSpeedCamZoneClear",
    returnType = "V",
    parameters = listOf(),
)

/** Waze's newer one ("insights"): every segment ahead or under way, an average speed zone among them. */
private object SegmentInsightUpdatedFingerprint : Fingerprint(
    definingClass = "Lcom/waze/insights_infra/InsightsInfraNativeManager;",
    returnType = "V",
    parameters = listOf("Lcom/waze/jni/protos/insights_infra/SegmentInsightUpdate;"),
    custom = { method, _ -> method.name.startsWith("onSegmentInsightUpdated") },
)

/** The speedometer taking its state (speed, units, the road's limit); its "Not shown." log is unique to it. */
private object SpeedometerStateFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("SpeedometerView: Not shown. (speed == "),
)

@Suppress("unused")
val wazeZoneBarPatch = bytecodePatch(
    name = "Average speed zone bar",
    description = "Bar with progress and your average speed in an average speed zone. Off until switched on.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeCustomizationPatch)

    execute {
        AverageSpeedZoneUpdateFingerprint.method.addInstruction(0, "invoke-static/range { p1 .. p1 }, $ZONE_BAR->legacy(Ljava/lang/Object;)V")
        AverageSpeedZoneClearFingerprint.method.addInstruction(0, "invoke-static {}, $ZONE_BAR->legacyClear()V")
        SegmentInsightUpdatedFingerprint.method.addInstruction(0, "invoke-static/range { p1 .. p1 }, $ZONE_BAR->insights(Ljava/lang/Object;)V")
        SpeedometerStateFingerprint.method.addInstruction(0, "invoke-static/range { p1 .. p1 }, $ZONE_BAR->speedometer(Ljava/lang/Object;)V")
        WazeApplicationCreateFingerprint.method.addInstruction(0, "invoke-static { p0 }, $ZONE_BAR->init(Landroid/app/Application;)V")
        declareWazeToggle("zoneBar")
        // Belgium's rule: 6 km/h up to 100 km/h, 6% above it.
        declareWazeValue("zoneBar", "ZONE_TOLERANCE_PERCENT", 6)
        declareWazeValue("zoneBar", "ZONE_TOLERANCE_MIN", 6)
    }
}
