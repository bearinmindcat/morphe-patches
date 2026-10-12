package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** org.ungoogled.ui.SpeedAlerts, the extension half. */
private const val SPEED_ALERTS = "Lorg/ungoogled/ui/SpeedAlerts;"

/**
 * The navigation speedometer's speed setter: metres a second in, times 3.6 for km/h or 2.2369363
 * for mph, stored as the whole number the speedometer shows. Those two factors appear together
 * nowhere else.
 */
private object SpeedometerSpeedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(literal(3.6f), literal(2.2369363f)),
)

@Suppress("unused")
val speedingChimePatch = bytecodePatch(
    name = "Speeding chime",
    description = "Plays a chime when your speed passes the speed limit during navigation, as Waze does. " +
        "Maps itself only turns its speedometer red. When it chimes is chosen in Waze & external data source, a row on the " +
        "account sheet.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, wazeFeaturesRowPatch)

    execute {
        fun Instruction.ref() = (this as? ReferenceInstruction)?.reference

        val setSpeed = SpeedometerSpeedFingerprint.method
        val code = setSpeed.implementation!!.instructions.toList()
        // The speedometer: what the method's holder is cast to before its fields are read.
        val speedometer = code.firstNotNullOfOrNull { insn ->
            (insn.ref() as? TypeReference)?.type?.takeIf { insn.opcode == Opcode.CHECK_CAST }
        } ?: throw PatchException("the speed setter casts to no speedometer")
        // Its units: read and compared with one enum constant -- the one that picks the 3.6 factor, km/h.
        val units = code.firstNotNullOfOrNull { insn ->
            (insn.ref() as? FieldReference)?.takeIf { insn.opcode == Opcode.IGET_OBJECT && it.definingClass == speedometer }
        } ?: throw PatchException("$speedometer's units field not found")
        val kmh = code.firstNotNullOfOrNull { insn ->
            (insn.ref() as? FieldReference)?.takeIf { insn.opcode == Opcode.SGET_OBJECT && it.type == units.type }
        } ?: throw PatchException("the km/h constant of ${units.type} not found")
        // The speed: the int field the converted value is stored in.
        val speedStore = code.indexOfLast { insn ->
            insn.opcode == Opcode.IPUT && (insn.ref() as FieldReference).let { it.definingClass == speedometer && it.type == "I" }
        }
        if (speedStore < 0) throw PatchException("$speedometer's speed field not found")
        val speedField = code[speedStore].ref() as FieldReference
        val holder = (code[speedStore] as TwoRegisterInstruction).registerB

        // The speed limit setter, beside it: (limit, units), storing the limit in another int field.
        val setLimit = mutableClassDefBy(setSpeed.definingClass).methods.singleOrNull {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("I", units.type)
        } ?: throw PatchException("the speed limit setter not found")
        val limitCode = setLimit.implementation!!.instructions.toList()
        val limitStore = limitCode.indexOfFirst { insn ->
            insn.opcode == Opcode.IPUT && (insn.ref() as FieldReference).let {
                it.definingClass == speedometer && it.type == "I" && it.name != speedField.name
            }
        }
        if (limitStore < 0) throw PatchException("$speedometer's speed limit field not found")
        val limitField = limitCode[limitStore].ref() as FieldReference
        val limitHolder = (limitCode[limitStore] as TwoRegisterInstruction).registerB

        // Each new speed, and each new limit, goes to the extension. Both inserts follow a store
        // that nothing branches past, and the holder register is still the speedometer there.
        setSpeed.addInstruction(speedStore + 1, "invoke-static { v$holder }, $SPEED_ALERTS->onSpeedometer(Ljava/lang/Object;)V")
        setLimit.addInstruction(limitStore + 1, "invoke-static { v$limitHolder }, $SPEED_ALERTS->onSpeedometer(Ljava/lang/Object;)V")

        // The extension's readers of that speedometer.
        val extension = mutableClassDefBy(SPEED_ALERTS)
        fun replace(name: String, returnType: String, smali: String) {
            extension.methods.remove(extension.methods.single { it.name == name })
            extension.methods.add(
                ImmutableMethod(
                    SPEED_ALERTS, name, listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), returnType,
                    AccessFlags.STATIC.value, null, null, MutableMethodImplementation(2),
                ).toMutable().apply { addInstructions(0, smali) },
            )
        }
        replace(
            "speed", "I",
            """
                check-cast p0, $speedometer
                iget v0, p0, $speedometer->${speedField.name}:I
                return v0
            """,
        )
        replace(
            "limit", "I",
            """
                check-cast p0, $speedometer
                iget v0, p0, $speedometer->${limitField.name}:I
                return v0
            """,
        )
        replace(
            "kmh", "Z",
            """
                check-cast p0, $speedometer
                iget-object v0, p0, $speedometer->${units.name}:${units.type}
                sget-object p0, ${kmh.definingClass}->${kmh.name}:${kmh.type}
                if-ne v0, p0, :not_kmh
                const/4 v0, 0x1
                return v0
                :not_kmh
                const/4 v0, 0x0
                return v0
            """,
        )

        markPatched("speedingChimePatched")
    }
}
