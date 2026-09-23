package org.ungoogled.patches.maps.layout

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val hideLoginPromoPatch = bytecodePatch(
    name = "Hide login promo",
    description = "Hides the full-screen \"Make it your map\" page shown on first launch.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
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
