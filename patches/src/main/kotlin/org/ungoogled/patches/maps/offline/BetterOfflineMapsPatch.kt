package org.ungoogled.patches.maps.offline

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

private const val PICKER = "Lorg/ungoogled/ui/OfflinePicker;"

/**
 * The live offline area picker's layout callback (a Compose lambda; the old
 * view-based picker has the same clamp but is dead code on this build). It
 * takes the viewport minus an inset, shrinks it to a centred box whose
 * estimate fits under the size cap (the view model's long, read just before
 * `RectF.width()`), then writes the box to a local state (which draws the
 * highlight) and, offset into window coordinates, to the model's window
 * state (which the size estimate and the download use). Found by shape only:
 * every Maps name (model, state interface, its accessors) is read from here.
 */
internal object OfflinePickerLayoutFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_WIDE, type = "J"),
        methodCall(definingClass = "Landroid/graphics/RectF;", name = "width"),
        methodCall(definingClass = "Ljava/lang/Math;", name = "sqrt"),
        methodCall(definingClass = "Ljava/lang/Math;", name = "sqrt"),
        methodCall(definingClass = "Landroid/graphics/RectF;", name = "offset"),
    ),
)

private fun BuilderInstruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun BuilderInstruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** Compose MutableState.setValue: an interface call `(Object)V` on the state interface. */
private fun BuilderInstruction.isStateSet(state: String): Boolean =
    opcode == Opcode.INVOKE_INTERFACE && method()?.let {
        it.definingClass == state && it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/Object;") &&
            it.returnType == "V"
    } == true

@Suppress("unused")
val betterOfflineMapsPatch = bytecodePatch(
    name = "Better offline maps",
    description = "Reworks the offline area picker: zooming out really selects more instead of being " +
        "shrunk to Google's size cap, the box can be resized by dragging its edges and corners, a large " +
        "area is split into several downloads whose true total size is shown, and areas already " +
        "downloaded are drawn on the map. Can be turned off on the Customization screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("betterOfflinePatched")

        OfflinePickerLayoutFingerprint.method.apply {
            val instructions = implementation!!.instructions
            fun reg(i: Int) = (instructions[i] as OneRegisterInstruction).registerA

            // The size cap: the model's long, read right before the viewport's width().
            val capRead = OfflinePickerLayoutFingerprint.instructionMatches.first().index
            if (OfflinePickerLayoutFingerprint.instructionMatches[1].index != capRead + 1) {
                throw PatchException("the size cap is no longer read right before the viewport's width")
            }
            val cap = instructions[capRead].field()!!
            val model = cap.definingClass
            // The model's window state: its field of the state type whose setValue ends the method.
            val stateFields = mutableClassDefBy(model).fields.filter { it.type.startsWith("L") }
            val windowWrites = instructions.indices.filter { i ->
                i > 0 && instructions[i].opcode == Opcode.INVOKE_INTERFACE &&
                    instructions[i - 1].opcode == Opcode.IGET_OBJECT &&
                    instructions[i - 1].field()?.definingClass == model &&
                    instructions[i].method()?.let { it.definingClass == instructions[i - 1].field()!!.type } == true &&
                    instructions[i].isStateSet(instructions[i - 1].field()!!.type)
            }
            val windowWrite = windowWrites.singleOrNull() ?: throw PatchException("window selection write found ${windowWrites.size} times")
            val windowField = instructions[windowWrite - 1].field()!!
            val state = windowField.type
            if (stateFields.none { it.name == windowField.name }) throw PatchException("window state is not a field of $model")
            val stateSet = instructions[windowWrite].method()!!.name
            // Its getValue: the no-argument interface call that reads another model state (the
            // pixels-per-metre scale) before the cap test.
            val stateGet = (0 until capRead).firstNotNullOfOrNull { i ->
                val prev = instructions.getOrNull(i - 1)?.field()
                instructions[i].method()?.takeIf {
                    instructions[i].opcode == Opcode.INVOKE_INTERFACE && it.parameterTypes.isEmpty() &&
                        it.returnType == "Ljava/lang/Object;" && prev?.definingClass == model && prev.type == state
                }?.name
            } ?: throw PatchException("the picker's state getter not found")
            val activityField = mutableClassDefBy(model).fields.singleOrNull { it.type == "Landroid/app/Activity;" }
                ?: throw PatchException("the picker model has no single Activity field")

            // 1. The area-vs-cap test: the first `cmpg-double` after the cap read that feeds
            //    an `if-lez`, with the shrink (Math.sqrt) right behind it.
            val test = (capRead + 1 until instructions.size - 1).firstOrNull {
                instructions[it].opcode == Opcode.CMPG_DOUBLE && instructions[it + 1].opcode == Opcode.IF_LEZ &&
                    (instructions[it] as ThreeRegisterInstruction).registerA == reg(it + 1)
            } ?: throw PatchException("area-vs-cap test not found")
            val shrinks = (test + 2 until minOf(test + 20, instructions.size)).any {
                ((instructions[it] as? ReferenceInstruction)?.reference as? MethodReference)?.name == "sqrt"
            }
            if (!shrinks) throw PatchException("the shrink no longer follows the area-vs-cap test")
            if (instructions[test].location.labels.isNotEmpty()) throw PatchException("area-vs-cap test is a branch target")
            val flag = (instructions[test] as ThreeRegisterInstruction).registerA
            val notTooBig = (instructions[test + 1] as BuilderOffsetInstruction).target.location.instruction
                ?: throw PatchException("area-vs-cap branch has no target")
            // The gate leaves our flag in the test's own result register, which is only
            // safe because the "not too big" path overwrites that register before any read.
            if (notTooBig.opcode !in listOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16) ||
                (notTooBig as TwoRegisterInstruction).registerA != flag || notTooBig.registerB == flag
            ) throw PatchException("the not-too-big path no longer starts by overwriting v$flag")

            // 2. The local state write: `iget-object vS, this.<captured Object>` then `state.setValue(vR)`.
            val localWrites = instructions.indices.filter {
                it > 0 && instructions[it].isStateSet(state) && instructions[it - 1].opcode == Opcode.IGET_OBJECT &&
                    instructions[it - 1].field()?.let { f -> f.definingClass == definingClass && f.type == "Ljava/lang/Object;" } == true
            }
            val localWrite = localWrites.singleOrNull() ?: throw PatchException("local selection write found ${localWrites.size} times")
            val local = instructions[localWrite] as Instruction35c
            if (local.registerC != reg(localWrite - 1)) throw PatchException("local selection write uses another state")
            if (instructions[localWrite].location.labels.isNotEmpty()) throw PatchException("local selection write is a branch target")

            // 3. The window state write: `iget-object vW, vM, model.h` then `state.setValue(vWR)`,
            //    after which the lambda returns Unit.
            val window = instructions[windowWrite] as Instruction35c
            val modelReg = (instructions[windowWrite - 1] as TwoRegisterInstruction).registerB
            if (window.registerC != reg(windowWrite - 1)) throw PatchException("window selection write uses another state")
            if (instructions[windowWrite + 1].opcode != Opcode.SGET_OBJECT ||
                instructions[windowWrite + 2].opcode != Opcode.RETURN_OBJECT ||
                reg(windowWrite + 1) != reg(windowWrite + 2)
            ) throw PatchException("the window selection write no longer ends the method")
            if (!(test < localWrite && localWrite < windowWrite)) throw PatchException("picker callback changed order")

            val used = setOf(window.registerC, window.registerD, modelReg)
            val scratch = (0..15).first { it !in used }
            if (listOf(local.registerC, local.registerD, window.registerC, window.registerD, modelReg).any { it > 15 }) {
                throw PatchException("picker registers out of range for invoke-static")
            }

            // Bottom-up, so the earlier indices stay valid.
            addInstructions(
                windowWrite + 1,
                """
                    iget-object v$scratch, v$modelReg, $model->${activityField.name}:Landroid/app/Activity;
                    invoke-static { v$scratch, v${window.registerC}, v${window.registerD}, v$modelReg }, $PICKER->pickerOverlay(Landroid/app/Activity;Ljava/lang/Object;Landroid/graphics/RectF;Ljava/lang/Object;)V
                """,
            )
            addInstructions(
                localWrite,
                """
                    invoke-static { v${local.registerC}, v${local.registerD} }, $PICKER->pickerRect(Ljava/lang/Object;Landroid/graphics/RectF;)Landroid/graphics/RectF;
                    move-result-object v${local.registerD}
                """,
            )
            addInstructionsWithLabels(
                test,
                """
                    sget-boolean v$flag, $SHAPES->BETTER_OFFLINE:Z
                    if-nez v$flag, :not_too_big
                """,
                ExternalLabel("not_too_big", notTooBig),
            )

            // The extension reaches the same objects by reflection: hand it the names found here.
            fun answer(name: String, value: String) {
                val getter = mutableClassDefBy(PICKER).methods.singleOrNull {
                    it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
                } ?: throw PatchException("$PICKER->$name() not found")
                val first = getter.implementation!!.instructions.first()
                if (first.opcode != Opcode.CONST_STRING) throw PatchException("$name() no longer starts with const-string")
                getter.replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$value\"")
            }
            answer("stateGetter", stateGet)
            answer("stateSetter", stateSet)
            answer("windowField", windowField.name)
            answer("activityField", activityField.name)
        }
    }
}
