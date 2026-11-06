package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/**
 * The start screen's row of saved places under "Where to?" (Waze's FavoritesCarousel): Home, Work, the named ones,
 * New / More. The row's builder adds each cell to a Compose LazyRow under a fixed key; Work's is "WORK_CELL_KEY".
 */
private object FavoritesWorkCellFingerprint : Fingerprint(
    strings = listOf("WORK_CELL_KEY"),
)

/**
 * The search screen's start list (Waze's SearchEmptyStateListBuilder): Home, Work, recents, More. It builds Work's
 * row from the saved place, or a "work" placeholder when none is set, and adds either to the list in one place.
 */
private object SearchStartListFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    strings = listOf("items", "ctas", "home", "work"),
)

private fun Instruction.isString(value: String) =
    opcode == Opcode.CONST_STRING && ((this as ReferenceInstruction).reference as StringReference).string == value

private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** A static call to [method] with the registers of [original], in the same encoding (range or not). */
private fun call(original: Instruction, method: String): String {
    val registers = original.registers()
    return if (original is RegisterRangeInstruction) {
        "invoke-static/range { v${registers.first()} .. v${registers.last()} }, $method"
    } else {
        "invoke-static { ${registers.joinToString(", ") { "v$it" }} }, $method"
    }
}

@Suppress("unused")
val wazeHideWorkPatch = bytecodePatch(
    name = "Hide Work shortcut",
    description = "Removes the Work button when choosing a destination.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeCustomizationPatch)

    execute {
        declareWazeToggle("hideWork")

        // The start screen's row: Work's cell goes through Customization.uaWorkCell, which adds it to the row unless
        // Hide Work shortcut is on. The wrapper takes exactly what Waze's call took.
        FavoritesWorkCellFingerprint.method.apply {
            val code = implementation!!.instructions.toList()
            val key = code.indexOfFirst { it.isString("WORK_CELL_KEY") }
            val keyRegister = (code[key] as OneRegisterInstruction).registerA
            val at = (key + 1 until minOf(code.size, key + 16)).firstOrNull { i ->
                val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
                (code[i].opcode == Opcode.INVOKE_STATIC || code[i].opcode == Opcode.INVOKE_STATIC_RANGE) &&
                    ref != null && ref.name.startsWith("item") && ref.returnType == "V" &&
                    ref.parameterTypes.firstOrNull()?.toString()?.endsWith("LazyListScope;") == true &&
                    code[i].registers().getOrNull(1) == keyRegister
            } ?: throw PatchException("the Work cell's LazyRow item call not found after WORK_CELL_KEY")
            val item = (code[at] as ReferenceInstruction).reference as MethodReference
            val types = item.parameterTypes.map { it.toString() }
            val size = types.sumOf { if (it == "J" || it == "D") 2 else 1 as Int }

            val customization = mutableClassDefBy(WAZE_CUSTOMIZATION)
            val wrapper = ImmutableMethod(
                WAZE_CUSTOMIZATION, "uaWorkCell", types.map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(size + 1),
            ).toMutable().apply {
                addInstructions(0, "return-void")
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, $WAZE_CUSTOMIZATION->hideWork()Z
                        move-result v0
                        if-nez v0, :hidden
                        invoke-static/range { p0 .. p${size - 1} }, $item
                    """,
                    ExternalLabel("hidden", implementation!!.instructions.first()),
                )
            }
            customization.methods.add(wrapper)
            replaceInstruction(
                at,
                call(code[at], "$WAZE_CUSTOMIZATION->uaWorkCell(${types.joinToString("")})V"),
            )
        }

        // The search screen's list: Work's row (saved place or placeholder) is added by the first List.add after the
        // "work" placeholder is made -- the place both branches meet. It goes through Customization.addWorkCell.
        SearchStartListFingerprint.method.apply {
            val code = implementation!!.instructions.toList()
            val work = code.indexOfFirst { it.isString("work") }
            val at = (work + 1 until code.size).firstOrNull { i ->
                val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference ?: return@firstOrNull false
                (code[i].opcode == Opcode.INVOKE_INTERFACE || code[i].opcode == Opcode.INVOKE_VIRTUAL) &&
                    ref.name == "add" && ref.returnType == "Z" &&
                    ref.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Object;")
            } ?: throw PatchException("the search list's Work row add not found")
            if (code[at].registers().size != 2) throw PatchException("the search list's Work row add changed shape")
            replaceInstruction(at, call(code[at], "$WAZE_CUSTOMIZATION->addWorkCell(Ljava/util/List;Ljava/lang/Object;)Z"))
        }
    }
}
