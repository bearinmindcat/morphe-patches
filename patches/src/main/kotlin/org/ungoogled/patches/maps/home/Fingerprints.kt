package org.ungoogled.patches.maps.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** The home fragment: the class answering its fragment id. */
internal object HomeFragmentIdFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(string("HOME_FRAGMENT_ID")),
)

/** The home sheet's states (HIDDEN, COLLAPSED, EXPANDED, FULLY_EXPANDED), an enum that kept its constant names. */
internal object SheetStateEnumFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(string("HIDDEN"), string("COLLAPSED"), string("EXPANDED"), string("FULLY_EXPANDED")),
)
