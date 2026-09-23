package org.ungoogled.patches.maps.search

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Type of the "Tired of typing? Sign in to save your searches" card item.
 * Resolved at patch time from the card class itself, so the method that
 * appends it can be found by what it instantiates rather than by name.
 */
internal lateinit var promoCardItemType: String

/** The card item's static initializer carries this trace tag; one in the APK. */
internal object PromoCardItemFingerprint : Fingerprint(
    name = "<clinit>",
    filters = listOf(string("SuggestPromoCardLayout")),
)

/**
 * The search screen's own appender for that card. The card is instantiated in
 * five places; four are constructors or take several arguments, and this is
 * the one single-argument void method: it checks signed-in / incognito state
 * and, if neither, appends the card to the suggestion list builder it is given.
 */
internal object AppendSignInPromoFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(newInstance({ promoCardItemType })),
)

/**
 * The second path: the gate the suggestion-list builder consults before adding
 * the card — signed-out and promo-eligible, combined with a per-instance flag,
 * boxed as a Boolean. No strings; the sequence is unique across the APK.
 */
internal object SignInPromoGateFingerprint : Fingerprint(
    returnType = "Ljava/lang/Boolean;",
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/Boolean;", name = "booleanValue"),
        methodCall(definingClass = "Ljava/lang/Boolean;", name = "valueOf"),
        methodCall(definingClass = "Ljava/lang/Object;", name = "getClass"),
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, definingClass = "this", type = "Z"),
        methodCall(definingClass = "Ljava/lang/Boolean;", name = "valueOf"),
    ),
)
