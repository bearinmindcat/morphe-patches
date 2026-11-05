package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.shared.addInstructionsAtLabel
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

/**
 * The speedometer's state (speed, units, the road's limit), a data class whose toString() names its fields. Waze makes
 * one for every change, for the phone's speedometer and the car screen's alike.
 */
private object SpeedometerStateToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("State(speed=", ", maxSpeedRoad="),
)

/**
 * Android Auto: the class that turns the car screen's bottom pill (current street, traffic, Waze's own zone countdown)
 * into the one picture Waze draws on the car's map; its constructor names its four picture makers.
 */
private object CarPillBitmapsFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    strings = listOf("averageSpeedCamBitmapGenerator", "insightBitmapGenerator"),
)

@Suppress("unused")
val wazeZoneBarPatch = bytecodePatch(
    name = "Average speed zone bar",
    description = "Bar with progress and your average speed in an average speed zone, Android Auto too. Off until switched on.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeCustomizationPatch)

    val position by stringOption(
        key = "position",
        default = "left",
        values = mapOf("Left edge" to "left", "Above the speed limit" to "speedometer", "Right edge" to "right"),
        title = "Position",
        description = "On the phone's screen. Android Auto shows it in Waze's bottom pill.",
        required = true,
    )

    execute {
        AverageSpeedZoneUpdateFingerprint.method.addInstruction(0, "invoke-static/range { p1 .. p1 }, $ZONE_BAR->legacy(Ljava/lang/Object;)V")
        AverageSpeedZoneClearFingerprint.method.addInstruction(0, "invoke-static {}, $ZONE_BAR->legacyClear()V")
        SegmentInsightUpdatedFingerprint.method.addInstruction(0, "invoke-static/range { p1 .. p1 }, $ZONE_BAR->insights(Ljava/lang/Object;)V")
        WazeApplicationCreateFingerprint.method.addInstruction(0, "invoke-static { p0 }, $ZONE_BAR->init(Landroid/app/Application;)V")

        // The limit: each speedometer state as it is made (every constructor, at its end).
        val state = mutableClassDefBy(SpeedometerStateToStringFingerprint.originalClassDef.type)
        val constructors = state.methods.filter { it.name == "<init>" }.ifEmpty { throw PatchException("${state.type} has no constructor") }
        for (constructor in constructors) {
            val code = constructor.implementation!!.instructions
            val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }.ifEmpty { throw PatchException("${state.type} constructor without return") }
            for (at in returns.reversed()) constructor.addInstructionsAtLabel(at, "invoke-static/range { p0 .. p0 }, $ZONE_BAR->speedometer(Ljava/lang/Object;)V")
        }

        // Android Auto (issue #99): the car pill's picture method -- Context, night, pill -- returning a holder of one
        // Bitmap. Its arguments are kept as it starts; whatever it returns goes through ZoneBar.carPill on its way out.
        val pills = mutableClassDefBy(CarPillBitmapsFingerprint.originalClassDef.type)
        val picture = pills.methods.singleOrNull { m ->
            m.parameterTypes.size == 3 && m.parameterTypes[0].toString() == "Landroid/content/Context;" &&
                m.parameterTypes[1].toString() == "Z" && m.returnType.startsWith("L")
        } ?: throw PatchException("the car pill's picture method not found in ${pills.type}")
        val code = picture.implementation!!.instructions
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.ifEmpty { throw PatchException("the car pill's picture method never returns") }
        for (at in returns.reversed()) {
            val register = (code[at] as OneRegisterInstruction).registerA
            picture.addInstructionsAtLabel(
                at,
                """
                    invoke-static/range { v$register .. v$register }, $ZONE_BAR->carPill(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                    check-cast v$register, ${picture.returnType}
                """,
            )
        }
        picture.addInstructionsAtLabel(0, "invoke-static/range { p1 .. p2 }, $ZONE_BAR->carPillStart(Ljava/lang/Object;Z)V")

        declareWazeToggle("zoneBar")
        // Belgium's rule: 6 km/h up to 100 km/h, 6% above it.
        declareWazeValue("zoneBar", "ZONE_TOLERANCE_PERCENT", 6)
        declareWazeValue("zoneBar", "ZONE_TOLERANCE_MIN", 6)
        // Where the bar goes on the phone (issue #91).
        declareWazeValue("zoneBar", "ZONE_POSITION", position!!)
    }
}
