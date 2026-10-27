package org.ungoogled.patches.waze

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE

/**
 * Sign-in prompts Waze raises on its own (issue #67). Its one such prompt is native: a "register your account" screen
 * shown once to users who lost their phone registration when Waze moved to its new sign-in, remembered by this flag.
 * Kept at "already shown", it never comes.
 *
 * Left alone, because the user asks for each: "Get started" and its sign-up step, "Log in", the chitchat button's
 * "you need to be a registered user" dialog, the profile's "Register this account" tip and "Register now", the inbox
 * log-out dialog, and the add_id action behind links and Waze's messages.
 */
@Suppress("unused")
val wazeRemoveSignInPromptsPatch = bytecodePatch(
    name = "Remove sign-in prompts",
    description = "Stops Waze's own reminder to register your account.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch)

    execute {
        keepWazeConfig("removeSignInPrompts", "CONFIG_VALUE_REALTIME_UID_DID_SHOW_REGISTER_ACCOUNT", true)
    }
}
