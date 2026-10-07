package org.ungoogled.patches.maps.account

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.layout.hideLoginPromoPatch
import org.ungoogled.patches.maps.search.removeSignInPromoPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/**
 * Every sign-in prompt in one patch: these used to be five (Hide login promo, Remove
 * sign-in promo, Hide sign-in button, Sign-in toast, Your profile toast). Kept as one
 * unit on purpose -- a build that signs in (microG) has to leave out all of them.
 */
@Suppress("unused")
val removeSignInPromptsPatch = bytecodePatch(
    name = "Remove sign-in prompts",
    description = "Removes Google's sign-in prompts: the first-launch \"Make it your map\" page, the search " +
        "screen's \"Tired of typing?\" card and the account sheet's \"Sign in\" pill. Tapping \"Your profile\" " +
        "(or the pill, where it still shows) answers with a \"Can't sign in\" toast instead of nothing.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(hideLoginPromoPatch, removeSignInPromoPatch, hideSignInButtonPatch, signInToastPatch, profileToastPatch)
}
