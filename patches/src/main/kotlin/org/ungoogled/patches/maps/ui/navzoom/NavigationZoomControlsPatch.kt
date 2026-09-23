package org.ungoogled.patches.maps.ui.navzoom

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/**
 * NavigationCameraControllerImpl.updateSharedCameraMode -- Maps names it in its
 * own trace label. Runs about once a second while guidance is live, and near
 * its end re-targets the camera through SharedCameraControllerImpl, which is
 * what pulls a held zoom back toward Maps' own.
 */
private object NavCameraUpdateFingerprint : Fingerprint(
    returnType = "Lj\$/util/Optional;",
    filters = listOf(
        string("NavigationCameraControllerImpl.updateSharedCameraMode"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, returnType = "Lj\$/util/Optional;", parameters = listOf("Lcfoq;", "Lblti;", "Z")),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
    ),
)

/** The same controller's teardown: it unregisters from the camera and releases the shared controller. */
private object NavCameraTeardownFingerprint : Fingerprint(
    classFingerprint = NavCameraUpdateFingerprint,
    name = "f",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "A", parameters = listOf("Lbjql;"))),
)

@Suppress("unused")
val navigationZoomControlsPatch = bytecodePatch(
    name = "Zoom controls in navigation",
    description = "Adds +, − and reset tiles during turn-by-turn that change the navigation zoom " +
        "while the camera keeps following the car.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("navZoomPatched")

        NavCameraUpdateFingerprint.let { fp ->
            // Capture before editing: the entry hook below shifts every index by one.
            val afterRetarget = fp.instructionMatches.last().index + 1
            // Re-assert a held zoom in the same call that just re-targeted the camera,
            // so the re-target is cancelled before anything is drawn. Only on that path.
            fp.method.addInstruction(afterRetarget, "invoke-static {}, $SHAPES->navReassert()V")
            // Publish the controller: `this` is v17 here, out of reach of the short invoke form.
            fp.method.addInstruction(0, "invoke-static/range { p0 .. p0 }, $SHAPES->navCam(Ljava/lang/Object;)V")
        }

        // An instant "navigation ended", instead of inferring it from the camera
        // hook going stale -- which left the tiles up for three seconds after the
        // rest of the drive screen had gone.
        NavCameraTeardownFingerprint.method.addInstruction(0, "invoke-static {}, $SHAPES->navEnded()V")
    }
}
