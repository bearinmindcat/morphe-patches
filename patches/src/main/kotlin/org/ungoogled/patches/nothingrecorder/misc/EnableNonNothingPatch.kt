package org.ungoogled.patches.nothingrecorder.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.nothingrecorder.NothingRecorderConstants.COMPATIBILITY_NOTHING_RECORDER

internal const val NOTHING_RECORDER_EXTENSION = "extensions/nothingrecorder.mpe"

/**
 * Off a Nothing phone, Nothing Recorder crashes at launch: it reflects on Nothing OS framework classes that only the
 * ROM provides (com.nothing.NtFeaturesUtils, the online-config reader, Nothing's analytics client, the NDot font
 * helper) and turns any missing one into a fatal error. The extension carries stand-ins for those classes that
 * answer "feature absent", so the app starts and recording, playback, the recordings list and search work on any
 * ROM; features that need Nothing's own services (home-screen widget, internal-audio capture) are out of scope.
 */
@Suppress("unused")
val enableNonNothingPatch = bytecodePatch(
    name = "Enable non-Nothing OS ROM support",
    description = "Makes Nothing Recorder installable and usable on non-Nothing OS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NOTHING_RECORDER)
    extendWith(NOTHING_RECORDER_EXTENSION)

    execute {
        // Fail loudly on a build that is not the expected Nothing-framework-reflecting one.
        var found = false
        classDefForEach classes@{ classDef ->
            if (found) return@classes
            for (method in classDef.methods) {
                val instructions = method.implementation?.instructions ?: continue
                if (
                    instructions.any {
                        it.opcode == Opcode.CONST_STRING &&
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                            "com.nothing.NtFeaturesUtils"
                    }
                ) {
                    found = true
                    break
                }
            }
        }
        if (!found) {
            throw PatchException("Nothing Recorder: the com.nothing.NtFeaturesUtils reference is gone; re-check the port.")
        }
    }
}
