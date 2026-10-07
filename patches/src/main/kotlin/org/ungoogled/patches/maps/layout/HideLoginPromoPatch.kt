package org.ungoogled.patches.maps.layout

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.maps.microg.MicrogSelection

internal val hideLoginPromoPatch = bytecodePatch(
    description = "Hides the full-screen \"Make it your map\" page shown on first launch.",
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        if (MicrogSelection.replaces(this, "Remove sign-in prompts")) return@execute
        // The gate reads its discriminator field and branches away before this
        // string is loaded, so execution only reaches the string when this is
        // the login-promo instance. Returning false right here disables that
        // one page and leaves the other provider sharing this method untouched.
        //
        // v0 is safe to clobber: the very instruction we insert in front of
        // overwrites it, so nothing downstream reads the old value.
        val loginPromoBranch = CanShowLoginPromoFingerprint.instructionMatches.first().index

        CanShowLoginPromoFingerprint.method.addInstructions(
            loginPromoBranch,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}
