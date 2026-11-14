package org.ungoogled.patches.maps.ui.navzoom

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.sharedExtensionPatch

/**
 * NavigationCameraControllerImpl.updateSharedCameraMode -- Maps names it in its
 * own trace label. Runs about once a second while guidance is live, and near
 * its end re-targets the camera through SharedCameraControllerImpl, which is
 * what pulls a held zoom back toward Maps' own.
 */
private object NavCameraUpdateFingerprint : Fingerprint(
    returnType = "Lj\$/util/Optional;",
    filters = listOf(string("NavigationCameraControllerImpl.updateSharedCameraMode")),
)

private fun MethodReference.isRetarget() =
    returnType == "Lj\$/util/Optional;" && parameterTypes.size == 3 && parameterTypes.last() == "Z"

/**
 * Tells the extension that turn-by-turn is live (Shapes.navCam, about once a second)
 * and when it ends (Shapes.navEnded) -- the signal behind every navigation overlay of
 * ours: the zoom tiles, and Power Saving Options' button, idle switch and frame rate.
 * Shared, so either patch can be applied without the other.
 *
 * Found by shape, not by name: the re-target is the update's only virtual call that
 * returns an Optional from (state, camera, boolean); the teardown is the controller's
 * no-argument method that unregisters it -- passes `this` as the one argument, typed as
 * one of the controller's own interfaces (its registration passes an Executor too).
 */
internal val navigationCameraHookPatch = bytecodePatch(
    description = "Lets the extension know when turn-by-turn navigation starts and ends.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        val update = NavCameraUpdateFingerprint.method
        val instructions = update.implementation!!.instructions
        val retargets = instructions.withIndex().filter { (i, insn) ->
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                ((insn as ReferenceInstruction).reference as MethodReference).isRetarget() &&
                instructions.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
        }
        val retarget = retargets.singleOrNull()?.index
            ?: throw PatchException("navigation camera re-target call: found ${retargets.size}, expected 1")
        // Capture before editing: the entry hook below shifts every index by one.
        val afterRetarget = retarget + 2
        // Re-assert a held zoom in the same call that just re-targeted the camera,
        // so the re-target is cancelled before anything is drawn. Only on that path;
        // a no-op while no zoom is held.
        update.addInstruction(afterRetarget, "invoke-static {}, $SHAPES->navReassert()V")
        // Publish the controller: `this` may sit above v15, out of reach of the short invoke form.
        update.addInstruction(0, "invoke-static/range { p0 .. p0 }, $SHAPES->navCam(Ljava/lang/Object;)V")

        // An instant "navigation ended", instead of inferring it from the camera
        // hook going stale -- which left the tiles up for three seconds after the
        // rest of the drive screen had gone.
        val controller = mutableClassDefBy(update.definingClass)
        val interfaces = controller.interfaces.toSet()
        val teardowns = controller.methods.filter { m ->
            m.returnType == "V" && m.parameterTypes.isEmpty() && m.implementation?.instructions?.any { insn ->
                if (insn.opcode != Opcode.INVOKE_VIRTUAL) return@any false
                val ref = (insn as ReferenceInstruction).reference as MethodReference
                val p0 = m.implementation!!.registerCount - 1
                ref.parameterTypes.size == 1 && ref.parameterTypes[0].toString() in interfaces &&
                    (insn as Instruction35c).registerD == p0
            } == true
        }
        val teardown = teardowns.singleOrNull()
            ?: throw PatchException("navigation camera teardown: found ${teardowns.size} (${teardowns.map { it.name }}), expected 1")
        if (teardown.implementation!!.instructions.first().location.labels.isNotEmpty()) {
            throw PatchException("navigation camera teardown starts at a branch target")
        }
        teardown.addInstruction(0, "invoke-static {}, $SHAPES->navEnded()V")
    }
}
