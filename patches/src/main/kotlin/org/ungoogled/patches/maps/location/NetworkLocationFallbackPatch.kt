package org.ungoogled.patches.maps.location

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val networkLocationFallbackPatch = bytecodePatch(
    name = "Network location fallback",
    description = "Keeps the network (Wi-Fi/cell) location provider registered when no fused " +
        "location provider answers, instead of GPS-only, so a fix does not go stale indoors.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        // Swap the network provider's STOP call for a START call, reusing the
        // looper already loaded for the GPS start two instructions earlier is
        // avoided in favour of a fresh load, matching the equivalent shell
        // patch's own choice not to rely on that value still being live.
        val stopCall = GpsOnlyModeFingerprint.instructionMatches.last()

        GpsOnlyModeFingerprint.method.apply {
            removeInstructions(stopCall.index, 1)
            addInstructions(
                stopCall.index,
                """
                    iget-object v1, p0, Laiql;->x:Landroid/os/Looper;
                    invoke-virtual {v0, v1}, Laiqk;->b(Landroid/os/Looper;)V
                """,
            )
        }
    }
}
