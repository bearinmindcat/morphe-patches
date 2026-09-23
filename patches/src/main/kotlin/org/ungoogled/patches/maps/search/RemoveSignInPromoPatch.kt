package org.ungoogled.patches.maps.search

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val removeSignInPromoPatch = bytecodePatch(
    name = "Remove sign-in promo",
    description = "Removes the \"Tired of typing?\" sign-in card from the search screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        // Maps has two independent builders for this card; either alone shows it.

        // 1. The appender the search screen actually uses: never append.
        promoCardItemType = PromoCardItemFingerprint.originalClassDef.type
        AppendSignInPromoFingerprint.method.addInstructions(0, "return-void")

        // 2. The gate the other builder consults: always "no". v0 is a free
        //    local (the method takes no arguments beyond `this`).
        SignInPromoGateFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
                return-object v0
            """,
        )
    }
}
