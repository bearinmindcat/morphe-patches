package org.ungoogled.patches.maps.misc

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.home.hideExploreFeedPatch
import org.ungoogled.patches.maps.home.hideNavigationTabsPatch
import org.ungoogled.patches.maps.placesheet.hideAiPatch
import org.ungoogled.patches.maps.placesheet.hideDirectoryCarouselPatch
import org.ungoogled.patches.maps.search.ads.hideAdsPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/**
 * Used to be five patches (Hide ads, Hide AI, Hide suggestions, Hide explore feed, Hide
 * navigation tabs). Each part still has its own switch on the Customization screen, so
 * one patch loses nothing.
 */
@Suppress("unused")
val hideAdsAndClutterPatch = bytecodePatch(
    name = "Hide ads and clutter",
    description = "Hides promoted map pins and \"Sponsored\" search results, Gemini's AI summaries (\"Know " +
        "before you go\" and the review summary), the row of businesses under an address on its place " +
        "sheet, the home tab's Explore feed and the Explore / Contribute / You tabs. Each can be switched " +
        "back on on the Customization screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(hideAdsPatch, hideAiPatch, hideDirectoryCarouselPatch, hideExploreFeedPatch, hideNavigationTabsPatch)
}
