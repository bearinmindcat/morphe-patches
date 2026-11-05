package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/** org.ungoogled.waze.ZoneBar, the runtime half (extensions/waze). */
private const val ZONE_BAR = "Lorg/ungoogled/waze/ZoneBar;"

/**
 * Waze's older zone display: the share of an average speed zone driven and the metres left -- the one
 * NavigationInfoNativeManager method taking an AverageSpeedCameraZoneUpdate (onAverageSpeedCamZoneUpdate in 5.24;
 * renamed in 5.26, where the JNI half parses the bytes and posts it to the main thread). A JNI proto, so its name stays.
 */
private object AverageSpeedZoneUpdateFingerprint : Fingerprint(
    definingClass = "Lcom/waze/navigate/NavigationInfoNativeManager;",
    returnType = "V",
    parameters = listOf("Lcom/waze/jni/protos/navigate/AverageSpeedCameraZoneUpdate;"),
)

/**
 * ... and the zone's end: onAverageSpeedCamZoneClear (5.24), or the JNI method onAverageSpeedCamZoneClearJNI of
 * NavigationInfoNativeManagerBase (5.26), which runs on Waze's native thread; ZoneBar moves it to the main thread.
 */
private object AverageSpeedZoneClearFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    custom = { method, classDef ->
        classDef.type.startsWith("Lcom/waze/navigate/NavigationInfoNativeManager") &&
            (method.name == "onAverageSpeedCamZoneClear" || method.name == "onAverageSpeedCamZoneClearJNI")
    },
)

/**
 * Waze's newer one ("insights"): every segment ahead or under way, an average speed zone among them -- the one
 * InsightsInfraNativeManager method taking a SegmentInsightUpdate (onSegmentInsightUpdated in 5.24, renamed in 5.26).
 */
private object SegmentInsightUpdatedFingerprint : Fingerprint(
    definingClass = "Lcom/waze/insights_infra/InsightsInfraNativeManager;",
    returnType = "V",
    parameters = listOf("Lcom/waze/jni/protos/insights_infra/SegmentInsightUpdate;"),
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
