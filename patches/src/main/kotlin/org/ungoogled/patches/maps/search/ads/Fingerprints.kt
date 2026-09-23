package org.ungoogled.patches.maps.search.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * The four pieces of "hide ads" (amro, atyc, aiql live in ../../gms and
 * ../../misc; these four cover the search-results ad path). The oneof case
 * `chaq.c == 5` is the ad discriminator, not the `chaq.b & 1` bit, which is
 * set on every server-driven row regardless of whether it is sponsored --
 * getting this wrong (as an earlier round of this project's own shell-patch
 * work did) empties the whole results list instead of just the ad rows.
 */
/** The chaq oneof discriminator value for a sponsored/ad entry. */
internal const val AD_CASE = 5

/** The private method that builds the sponsored-ads list from a search response. */
internal object AdsListBuilderFingerprint : Fingerprint(
    name = "ap",
    returnType = "Ljava/util/List;",
    parameters = listOf("Lcqgm;", "Lcffu;"),
)

/**
 * Where the results processor decides whether to also merge in the response's
 * sponsored PLACEMARKS (as opposed to the unified list's ad rows, handled by
 * `an()` below): it calls the builder above, then branches on whether the
 * caller opted in. One call site for the builder in the whole APK.
 */
internal object SponsoredPlacemarksGateFingerprint : Fingerprint(
    name = "aj",
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "Lavdv;", name = "ap", opcode = Opcode.INVOKE_DIRECT),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, MatchAfterImmediately()),
    ),
)

/**
 * The unified-list loop's own head: fetch the next entry and cast it. Ad
 * entries (case 5) are skipped here before either of the loop body's two
 * branches run, which is what "skipped at the loop top" means.
 */
internal object UnifiedListLoopHeadFingerprint : Fingerprint(
    name = "an",
    returnType = "Lavdt;",
    filters = listOf(
        methodCall(definingClass = "Ljava/util/Iterator;", name = "hasNext", opcode = Opcode.INVOKE_INTERFACE),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, MatchAfterImmediately()),
        methodCall(definingClass = "Ljava/util/Iterator;", name = "next", opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        checkCast("Lchaq;", location = MatchAfterImmediately()),
    ),
)

/**
 * The loop's second, independent ad-building path (a fallback list, not the
 * chaq oneof): a placemark it just built answers its own is-ad query, which
 * is a genuinely separate signal from the chaq case above -- confirmed by the
 * shell patch this replaces needing both gates to fully hide sponsored
 * content live.
 */
internal object FallbackPlacemarkFingerprint : Fingerprint(
    name = "an",
    returnType = "Lavdt;",
    filters = listOf(
        methodCall(definingClass = "Lavdv;", name = "am", opcode = Opcode.INVOKE_STATIC),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "Lokx;", name = "a", opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
    ),
)

/** The sponsored-pin/ad placemark object's static factory, built from the ads proto. */
internal object AdPlacemarkFactoryFingerprint : Fingerprint(
    name = "c",
    returnType = "Lamro;",
    parameters = listOf("Lceyh;"),
)

/**
 * The sponsored search-results ROW builder's card-model handoff: the row's
 * view model is built and assigned to the register the rest of the method
 * inserts into the results list.
 */
internal object SponsoredRowHandoffFingerprint : Fingerprint(
    name = "v",
    returnType = "V",
    parameters = listOf("Lavdv;"),
    filters = listOf(
        methodCall(definingClass = "Lakkw;", name = "j", opcode = Opcode.INVOKE_VIRTUAL),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        opcode(Opcode.MOVE, MatchAfterImmediately()),
        opcode(Opcode.MOVE_OBJECT, MatchAfterImmediately()),
    ),
)

/**
 * The same method's row-list insertion guard: skipped when the row's view
 * model is null. Its own branch target -- what happens when there is
 * nothing to insert -- is exactly where a sponsored row needs to jump to,
 * so this fingerprint exists only to read that target, not to be edited.
 */
internal object SponsoredRowInsertGuardFingerprint : Fingerprint(
    name = "v",
    returnType = "V",
    parameters = listOf("Lavdv;"),
    filters = listOf(
        opcode(Opcode.IF_EQZ),
        methodCall(
            definingClass = "Ljava/util/List;",
            name = "add",
            parameters = listOf("I", "Ljava/lang/Object;"),
            opcode = Opcode.INVOKE_INTERFACE,
            location = MatchAfterImmediately(),
        ),
    ),
)
