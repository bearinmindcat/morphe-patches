package org.ungoogled.patches.maps.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * The home fragment's sheet-state resolver, at the point every path (saved
 * state, tab type, server config, or the EXPANDED fallback) has converged on
 * one value before it is handed to the fragment transition. Overwriting the
 * register here, regardless of which path set it, is simpler and more robust
 * than gating every individual path.
 */
internal object ExploreSheetStateFingerprint : Fingerprint(
    name = "bb",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(opcode = Opcode.SGET_OBJECT, definingClass = "Lafpc;", name = "a", type = "Lpfi;"),
        opcode(Opcode.NEW_INSTANCE, MatchAfterImmediately()),
    ),
)

/** Where the home fragment binds the bottom tab strip's view holder, once. */
internal object BottomTabStripBindFingerprint : Fingerprint(
    filters = listOf(
        methodCall(definingClass = "Lafoz;", name = "u", opcode = Opcode.INVOKE_INTERFACE),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "Lafgr;", name = "da", location = MatchAfterImmediately()),
    ),
)
