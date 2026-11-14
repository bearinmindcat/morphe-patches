package org.ungoogled.patches.maps.layout

import org.ungoogled.patches.shared.addInstructionsAtLabel
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

internal val hideLoginPromoPatch = bytecodePatch(
    description = "Hides the full-screen \"Make it your map\" page shown on first launch.",
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        // microG Maps too (issue #80): the page only promotes signing in, which stays on the account sheet.
        // The gate reads its discriminator field and branches away before this
        // string is loaded, so execution only reaches the string when this is
        // the login-promo instance. Returning false right here disables that
        // one page and leaves the other provider sharing this method untouched.
        //
        // v0 is safe to clobber: the very instruction we insert in front of
        // overwrites it, so nothing downstream reads the old value.
        val loginPromoBranch = CanShowLoginPromoFingerprint.instructionMatches.first().index

        // At the string's own label: since 26.43 the branch for this provider jumps straight to it, so code put
        // in front of the label never ran.
        CanShowLoginPromoFingerprint.method.addInstructionsAtLabel(
            loginPromoBranch,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}
