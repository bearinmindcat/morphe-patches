package org.ungoogled.patches.samsunginternet

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.samsunginternet.SamsungInternetConstants.COMPATIBILITY_SAMSUNG_INTERNET

private const val SETTINGS_UTILS = "${SBROWSER}settings/utils/SettingsUtils;"

/**
 * When the store reports a newer Samsung Internet, SettingsUtils.hasNewUpdate() turns true and three places show it:
 * the badge count on Settings (which the menu button's red dot adds up), the dot on Settings > About Samsung Internet
 * and the update card at the top of Settings. Those three read "no update"; About itself still checks and offers the
 * update when opened.
 */
@Suppress("unused")
val hideUpdateBadgePatch = bytecodePatch(
    name = "Hide update badge",
    description = "Removes the update red dot from the menu and Settings.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_INTERNET)

    execute {
        val hasNewUpdate = call(SETTINGS_UTILS, "hasNewUpdate")
        for ((type, name) in listOf(
            SETTINGS_UTILS to "getSettingsBadgeCount",
            SETTINGS_UTILS to "shouldShowUpdateCard",
            "${SBROWSER}settings/SettingsFragment;" to "updatePreferencesDotVisibility",
        )) {
            if (method(type, name).answerFalse(hasNewUpdate) == 0) {
                throw PatchException("${type.simpleName()}.$name no longer asks whether there is an update")
            }
        }
    }
}
