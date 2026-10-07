package org.ungoogled.patches.maps.account

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.account.keepopen.keepAccountSheetOpenPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** Used to be two patches: Hide section title and Keep account sheet open. */
@Suppress("unused")
val accountSheetCleanupPatch = bytecodePatch(
    name = "Account sheet cleanup",
    description = "Removes the \"More from this app\" label from the account sheet, and keeps the sheet open " +
        "when you come back from Settings or Customization or tap \"Your profile\", instead of dropping " +
        "back to the map.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(hideSectionTitlePatch, keepAccountSheetOpenPatch)
}
