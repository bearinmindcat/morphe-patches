package org.ungoogled.patches.maps.account

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * The account sheet has two live "Your profile" row builders (confirmed live
 * via Frida: a fresh install lands on the legacy one), so both click actions
 * are replaced.
 *
 * Both builders are switch-dispatched, single-method closures shared by many
 * unrelated menu actions (the same pattern documented for Lbpfw;): `.method`
 * on a Fingerprint match is the WHOLE dispatcher, not just this row's case, so
 * every filter below is part of one contiguous span that pins the case's own
 * first and last real instruction. That span, and only that span, is what the
 * patch replaces.
 */

/** The modern builder's click case: read the row, open the profile, return the singleton. */
internal object ProfileClickModernFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "Lopa;", name = "a"),
        checkCast("Loyi;", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "Loyi;", name = "c", location = MatchAfterImmediately()),
        methodCall(definingClass = "Lcqpp;", name = "a", opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "Ljava/lang/Object;", name = "getClass", location = MatchAfterImmediately()),
        checkCast("Lbajm;", location = MatchAfterImmediately()),
        methodCall(definingClass = "Lbahc;", name = "i", parameters = listOf("Lbajm;"), location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.SGET_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_OBJECT, MatchAfterImmediately()),
    ),
)

/**
 * The legacy builder's click case: trace this exact row (the string is what
 * makes it this row and not a sibling case), open the profile inside a
 * try/finally that closes the trace span, with a nested finally that
 * re-throws with suppression. All 23 real instructions of the case are
 * covered so the removal span has an exact, self-verified length.
 */
internal object ProfileClickLegacyFingerprint : Fingerprint(
    name = "onClick",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "Lolq;", name = "a"),
        opcode(Opcode.MOVE_OBJECT, MatchAfterImmediately()),
        checkCast("Lolr;", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "Lolr;", name = "n", location = MatchAfterImmediately()),
        string("YourProfileActionSpecClicked", location = MatchAfterImmediately()),
        methodCall(definingClass = "Lbwey;", name = "a", opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        checkCast("Lolr;", location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "Lolr;", name = "h", location = MatchAfterImmediately()),
        methodCall(definingClass = "Lcqpp;", name = "a", opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        checkCast("Lbajm;", location = MatchAfterImmediately()),
        methodCall(definingClass = "Lbajm;", name = "b", opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        methodCall(definingClass = "Lbwcs;", name = "close", opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_VOID, MatchAfterImmediately()),
        opcode(Opcode.MOVE_EXCEPTION, MatchAfterImmediately()),
        opcode(Opcode.MOVE_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "Lbwcs;", name = "close", opcode = Opcode.INVOKE_INTERFACE, location = MatchAfterImmediately()),
        // The `goto` right after this may assemble to GOTO/GOTO_16/GOTO_32
        // depending on branch distance, which baksmali's mnemonic doesn't
        // reveal, so it is skipped rather than matched on opcode.
        opcode(Opcode.MOVE_EXCEPTION, MatchAfterWithin(2)),
        opcode(Opcode.MOVE_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "Ljava/lang/Throwable;", name = "addSuppressed", location = MatchAfterImmediately()),
        opcode(Opcode.THROW, MatchAfterImmediately()),
    ),
)
