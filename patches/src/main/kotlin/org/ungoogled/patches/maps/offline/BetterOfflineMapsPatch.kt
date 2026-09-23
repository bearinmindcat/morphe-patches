package org.ungoogled.patches.maps.offline

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
/** The picker's view model; c = the size cap, h = the selection in window coordinates, a = its Activity. */
private const val MODEL = "Laoph;"
/** Compose MutableState.setValue. */
private const val STATE_SET = "Ldxm;->d(Ljava/lang/Object;)V"

/**
 * The live offline area picker's layout callback (a Compose lambda; the old
 * view-based picker has the same clamp but is dead code on this build). It
 * takes the viewport minus an inset, shrinks it to a centred box whose
 * estimate fits under the size cap, then writes the box to a local state
 * (which draws the highlight) and, offset into window coordinates, to the
 * model's `h` (which the size estimate and the download use).
 */
internal object OfflinePickerLayoutFingerprint : Fingerprint(
    name = "a",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_WIDE, definingClass = MODEL, name = "c", type = "J"),
        methodCall(definingClass = "Ljava/lang/Math;", name = "sqrt"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = MODEL, name = "h", type = "Ldxm;"),
    ),
)

private fun BuilderInstruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun BuilderInstruction.isStateSet(): Boolean =
    opcode == Opcode.INVOKE_INTERFACE && ((this as ReferenceInstruction).reference as MethodReference).let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == STATE_SET
    }

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

            val capReads = instructions.indices.filter {
                instructions[it].field()?.let { f -> f.definingClass == MODEL && f.name == "c" } == true
            }
            val capRead = capReads.singleOrNull() ?: throw PatchException("size cap is read ${capReads.size} times")

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

            // 2. The local state write: `iget-object vS, this.c` then `state.setValue(vR)`.
            val localWrites = instructions.indices.filter {
                it > 0 && instructions[it].isStateSet() && instructions[it - 1].opcode == Opcode.IGET_OBJECT &&
                    instructions[it - 1].field()?.let { f -> f.definingClass == definingClass && f.name == "c" } == true
            }
            val localWrite = localWrites.singleOrNull() ?: throw PatchException("local selection write found ${localWrites.size} times")
            val local = instructions[localWrite] as Instruction35c
            if (local.registerC != reg(localWrite - 1)) throw PatchException("local selection write uses another state")
            if (instructions[localWrite].location.labels.isNotEmpty()) throw PatchException("local selection write is a branch target")

            // 3. The window state write: `iget-object vW, vM, model.h` then `state.setValue(vWR)`,
            //    after which the lambda returns Unit.
            val windowWrites = instructions.indices.filter {
                it > 0 && instructions[it].isStateSet() && instructions[it - 1].opcode == Opcode.IGET_OBJECT &&
                    instructions[it - 1].field()?.let { f -> f.definingClass == MODEL && f.name == "h" } == true
            }
            val windowWrite = windowWrites.singleOrNull() ?: throw PatchException("window selection write found ${windowWrites.size} times")
            val window = instructions[windowWrite] as Instruction35c
            val model = (instructions[windowWrite - 1] as TwoRegisterInstruction).registerB
            if (window.registerC != reg(windowWrite - 1)) throw PatchException("window selection write uses another state")
            if (instructions[windowWrite + 1].opcode != Opcode.SGET_OBJECT ||
                instructions[windowWrite + 2].opcode != Opcode.RETURN_OBJECT ||
                reg(windowWrite + 1) != reg(windowWrite + 2)
            ) throw PatchException("the window selection write no longer ends the method")
            if (!(test < localWrite && localWrite < windowWrite)) throw PatchException("picker callback changed order")

            val used = setOf(window.registerC, window.registerD, model)
            val scratch = (0..15).first { it !in used }
            if (listOf(local.registerC, local.registerD, window.registerC, window.registerD, model).any { it > 15 }) {
                throw PatchException("picker registers out of range for invoke-static")
            }

            // Bottom-up, so the earlier indices stay valid.
            addInstructions(
                windowWrite + 1,
                """
                    iget-object v$scratch, v$model, $MODEL->a:Landroid/app/Activity;
                    invoke-static { v$scratch, v${window.registerC}, v${window.registerD}, v$model }, $PICKER->pickerOverlay(Landroid/app/Activity;Ljava/lang/Object;Landroid/graphics/RectF;Ljava/lang/Object;)V
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
        }
    }
}
