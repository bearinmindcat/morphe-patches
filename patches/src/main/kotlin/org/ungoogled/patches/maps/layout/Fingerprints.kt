package org.ungoogled.patches.maps.layout

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/**
 * `LoginPromoTutorialController.canShowTutorial`.
 *
 * The first-launch "Make it your map" page is built by an onboarding page
 * provider that R8 has merged with at least one unrelated provider: the same
 * class serves the login promo and USER_LOCATION_REPORTING, told apart at
 * runtime by an int field. Its `canShowTutorial` gate therefore branches on
 * that field before doing any login-promo work.
 *
 * Matching on the class or method name is useless here — both are obfuscated
 * and change every app build. The trace label is not: Maps compiles these
 * strings into the dex for its own tracing, and this one occurs exactly once
 * in the entire APK (verified across all ten dex files), immediately at the
 * head of the login-promo branch. That makes it both a unique anchor and the
 * exact instruction we need to insert in front of.
 */
internal object CanShowLoginPromoFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(
        string("LoginPromoTutorialController.canShowTutorial"),
    ),
)
