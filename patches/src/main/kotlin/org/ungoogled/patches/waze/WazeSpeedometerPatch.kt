package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/** The speedometer's own layout; its views keep their ids (Waze's resources are not renamed). */
private const val SPEEDOMETER_LAYOUT = "res/layout/speedometer.xml"

/**
 * The speedometer view's bind method, the one that writes the speed into it. It logs
 * "SpeedometerView: Not shown." when there is nothing to show; the string is unique to it.
 */
private object SpeedometerBindFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("SpeedometerView: Not shown. (speed == "),
)

/**
 * The speed and its unit are sized in the layout: 26dp digits, 12dp "mph". Changed to the
 * patch's options here, before the layout is compiled back.
 */
private val speedometerLayoutPatch: ResourcePatch = resourcePatch {
    execute {
        val layout = get(SPEEDOMETER_LAYOUT)
        if (!layout.isFile) throw PatchException("$SPEEDOMETER_LAYOUT not found")
        val options = wazeBiggerSpeedometerPatch.options
        val sizes = mapOf(
            "speedText" to options["speed"]!!.value as Int,
            "speedUnits" to options["units"]!!.value as Int,
        )
        var text = layout.readText()
        for ((id, dp) in sizes) {
            var changed = 0
            // Each text view is one self-closing element; the one with this id gets the new size.
            text = Regex("<[A-Za-z0-9_.]+\\b[^>]*?/>").replace(text) { element ->
                if (!Regex("android:id=\"@\\+?id/$id\"").containsMatchIn(element.value)) return@replace element.value
                val sized = Regex("android:textSize=\"[^\"]*\"").replace(element.value, "android:textSize=\"$dp.0dip\"")
                if (sized != element.value) changed++
                sized
            }
            if (changed != 1) throw PatchException("$SPEEDOMETER_LAYOUT: $changed views with id $id and a text size, expected 1")
        }
        layout.writeText(text)
    }
}

@Suppress("unused")
val wazeBiggerSpeedometerPatch: BytecodePatch = bytecodePatch(
    name = "Bigger speedometer",
    description = "Bigger speedometer and speed limit digits.",
) {
    compatibleWith(COMPATIBILITY_WAZE)
    // The sizes are listed, and can be changed, on the Customization screen.
    dependsOn(speedometerLayoutPatch, wazeCustomizationPatch)

    val speed by intOption(
        key = "speed",
        default = 32,
        title = "Speed digits (dp)",
        description = "Waze: 26.",
        required = true,
    )
    val units by intOption(
        key = "units",
        default = 14,
        title = "Unit label (dp)",
        description = "The mph or km/h label. Waze: 12.",
        required = true,
    )
    val limit by intOption(
        key = "limit",
        default = 24,
        title = "Speed limit sign digits (sp)",
        description = "Limits under 100. Waze: 20.",
        required = true,
    )
    val limitThreeDigits by intOption(
        key = "limitThreeDigits",
        default = 16,
        title = "Speed limit sign digits, 100 and over (sp)",
        description = "Waze: 13.",
        required = true,
    )

    execute {
        // The sign's digits are sized in code: 20 under 100, 13 from 100, both then converted to a
        // float for setTextSize -- the only int-to-float in the method fed by those two constants.
        val bind = SpeedometerBindFingerprint.method
        val code = bind.implementation!!.instructions.toList()
        val toFloat = code.indexOfFirst { it.opcode == Opcode.INT_TO_FLOAT }
        if (toFloat < 0) throw PatchException("the speedometer bind has no int-to-float")
        val register = (code[toFloat] as TwoRegisterInstruction).registerB
        fun constant(value: Int) = (toFloat - 1 downTo maxOf(0, toFloat - 4)).firstOrNull { i ->
            val insn = code[i]
            insn is NarrowLiteralInstruction && insn is OneRegisterInstruction && insn.narrowLiteral == value &&
                insn.registerA == register
        } ?: throw PatchException("the speed limit sign size $value not found before the int-to-float")
        val twoDigits = constant(20)
        val threeDigits = constant(13)
        // A replace keeps the labels on these locations (the branch into the 13 case lands on it); each
        // size then goes through Customization, which gives the one chosen there (issue #50).
        bind.replaceInstruction(twoDigits, "const/16 v$register, ${limit!!}")
        bind.replaceInstruction(threeDigits, "const/16 v$register, ${limitThreeDigits!!}")
        for ((at, method) in listOf(twoDigits to "limitSize", threeDigits to "limitThreeSize").sortedByDescending { it.first }) {
            bind.addInstructions(
                at + 1,
                """
                    invoke-static/range { v$register .. v$register }, $WAZE_CUSTOMIZATION->$method(I)I
                    move-result v$register
                """,
            )
        }
        // The speed and its unit: sized from Customization as the speedometer (this) is bound.
        val self = bind.implementation!!.registerCount - bind.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 as Int } - 1
        bind.addInstructions(0, "invoke-static/range { v$self .. v$self }, $WAZE_CUSTOMIZATION->speedometer(Ljava/lang/Object;)V")

        declareWazeToggle("biggerSpeedometer")
        declareWazeValue("biggerSpeedometer", "SPEEDOMETER_SPEED", speed!!)
        declareWazeValue("biggerSpeedometer", "SPEEDOMETER_UNITS", units!!)
        declareWazeValue("biggerSpeedometer", "SPEEDOMETER_LIMIT", limit!!)
        declareWazeValue("biggerSpeedometer", "SPEEDOMETER_LIMIT_THREE", limitThreeDigits!!)
    }
}
