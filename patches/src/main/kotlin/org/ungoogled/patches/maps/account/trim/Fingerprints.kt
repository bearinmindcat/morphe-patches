package org.ungoogled.patches.maps.account.trim

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * The account sheet has two live builders for the same rows (confirmed live
 * via Frida: a fresh install lands on the legacy one), so both are trimmed.
 *
 * Every span here is bounded by a "kept" row's OWN, uniquely-named call --
 * never by the generic `move-object vN, v6 / check-cast vN, Lolr;` setup
 * that precedes EVERY row identically, which cannot tell one row's setup
 * from another's. A filter with no explicit location defaults to searching
 * forward from the previous match without a fixed window (confirmed from the
 * patcher's own bytecode: the omitted location parameter decompiles to
 * `MatchAfterAnywhere`, and -- read from the same bytecode -- when a
 * DOWNSTREAM filter with a NON-anywhere location fails to match, the engine
 * restarts the WHOLE chain from just past filter 0's match rather than
 * retrying the failing step's next candidate; since filter 0 below is
 * unique, that restart would make the whole fingerprint fail outright rather
 * than mismatch silently). A generic, repeated-shape filter as anything but
 * the very first or very last step is therefore never safe to use here --
 * only a step unique to the specific row is.
 */

/** Group 1 bounds: custom-actions list construction (kept, start) .. Offline maps' own call (kept, end). */
internal object CustomActionsListFingerprint : Fingerprint(
    name = "af",
    parameters = listOf("Lafmm;"),
    returnType = "Lbrhh;",
    filters = listOf(
        methodCall(definingClass = "Lbwxy;", name = "<init>", opcode = Opcode.INVOKE_DIRECT, parameters = listOf("I")),
        methodCall(definingClass = "Lolr;", name = "e", opcode = Opcode.INVOKE_VIRTUAL),
    ),
)

/**
 * Group 2 bounds: common-actions list construction (kept, start) .. Settings'
 * own field read (kept, end). `Lbwxy;-><init>(I)V` is called TWICE in this
 * method (once for the custom-actions list, once for this one) and a
 * methodCall filter cannot distinguish them by register, so this anchors on
 * Offline maps' own call first -- unique, and guaranteed to sit between the
 * two constructor calls -- and only then looks for the NEXT constructor
 * call, which forward-only scanning can resolve to nothing but this one.
 */
internal object CommonActionsListFingerprint : Fingerprint(
    name = "af",
    parameters = listOf("Lafmm;"),
    returnType = "Lbrhh;",
    filters = listOf(
        methodCall(definingClass = "Lolr;", name = "e", opcode = Opcode.INVOKE_VIRTUAL),
        methodCall(definingClass = "Lbwxy;", name = "<init>", opcode = Opcode.INVOKE_DIRECT, parameters = listOf("I")),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "Lolr;", name = "q"),
    ),
)

/** Group 3 bounds: Settings' own row-add (kept, start) .. the common-actions list finalize (kept, end). */
internal object SettingsRowAddFingerprint : Fingerprint(
    name = "af",
    parameters = listOf("Lafmm;"),
    returnType = "Lbrhh;",
    filters = listOf(
        methodCall(definingClass = "Lbsoa;", name = "aB", opcode = Opcode.INVOKE_STATIC),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "Lbwxy;", name = "i", opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
        methodCall(definingClass = "Lbwxy;", name = "h", opcode = Opcode.INVOKE_VIRTUAL),
    ),
)

/**
 * Legacy builder (Lolu;): unlike the modern builder, `Lbwxy;-><init>(I)V` is
 * ambiguous here too (called twice, same register name reused for both
 * lists), so neither span below references it -- each anchors directly on a
 * marker unique to the row it targets instead.
 */

/** Legacy: Settings' own row-add (kept, start) .. the common-actions list finalize (kept, end). */
internal object LegacySettingsRowAddFingerprint : Fingerprint(
    name = "a",
    parameters = listOf("Z"),
    returnType = "Lbrfb;",
    filters = listOf(
        methodCall(definingClass = "Lbsoa;", name = "aB", opcode = Opcode.INVOKE_STATIC),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "Lbwxy;", name = "i", opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
        methodCall(definingClass = "Lbwxy;", name = "h", opcode = Opcode.INVOKE_VIRTUAL),
    ),
)

/** Legacy: Your profile's own row-add (kept, start) .. Offline maps' own call (kept, end). */
internal object LegacyYourProfileFingerprint : Fingerprint(
    name = "a",
    parameters = listOf("Z"),
    returnType = "Lbrfb;",
    filters = listOf(
        methodCall(definingClass = "Lbrmt;", name = "a", opcode = Opcode.INVOKE_VIRTUAL),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "Lbwxy;", name = "i", opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
        methodCall(definingClass = "Lolr;", name = "e", opcode = Opcode.INVOKE_VIRTUAL),
    ),
)
