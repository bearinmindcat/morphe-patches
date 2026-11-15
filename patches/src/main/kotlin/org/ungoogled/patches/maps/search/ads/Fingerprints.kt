package org.ungoogled.patches.maps.search.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/*
 * Hide ads, found from four anchors that hold across Maps builds: Maps' own table of request protos
 * by RPC name, the ad POI's log messages, the search result's log messages and a promoted-hotel log
 * message. Everything else is read off those.
 */

/** Maps' table of request proto (by class name) -> RPC name: `map.put("<request>", "ListPromotedPinAds")`. */
internal object RpcNamesFingerprint : Fingerprint(
    filters = listOf(string("ListPromotedPinAds")),
)

/** The ad POI (a promoted pin's placemark); its static factory builds one from the ad proto. */
internal object AdPoiFingerprint : Fingerprint(
    filters = listOf(string("Ad POI doesn't support getMapLabel().")),
)

/** The search result: unified rows, sponsored placemarks and their merge. */
internal object SearchResultFingerprint : Fingerprint(
    filters = listOf(string("SearchResult#merge called with deserialized searchResult.")),
)

/** Promoted hotel logging: `for (place : result.places()) if (place.isAd() && ...)`. */
internal object PromotedHotelLogFingerprint : Fingerprint(
    filters = listOf(string("Tried to log promoted hotel's promoted partners but promoted logging URL is missing or empty.")),
)

/** Navigation's promoted-pin label controller, the only class with this trace label. */
internal object NavigationAdsLabelControllerFingerprint : Fingerprint(
    filters = listOf(string("NavigationAdsLabelController.onNavigationUiStateChanged")),
)
