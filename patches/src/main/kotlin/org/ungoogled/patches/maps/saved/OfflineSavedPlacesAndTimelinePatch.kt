package org.ungoogled.patches.maps.saved

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** Used to be two patches: Offline saved places and Offline timeline (which lives on its screen). */
@Suppress("unused")
val offlineSavedPlacesPatch = bytecodePatch(
    name = "Offline saved places",
    description = "Save places without a Google account, kept only on the phone: Save opens Maps' own \"Place " +
        "saved\" sheet (Want to go, Travel plans, Starred places, Favorites, your own lists, a note), and a " +
        "\"Local saved\" row on the account sheet rebuilds Maps' You tab -- your recent places, your lists and " +
        "labels (Home, Work, your own) -- with export and import (backup file, KML, Google Takeout's Saved " +
        "Places.json). It also has a Timeline: where the phone has been, grouped into days and visits, kept " +
        "only on the phone, with GPX export. Recording is off until switched on there; it shows a " +
        "notification while it runs. With Add microG support, Maps' own Save keeps syncing to your account " +
        "and Local saved instead copies the account's saved lists to the phone (Pull from Google account).",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(localSavedPlacesPatch, localTimelinePatch)
}
