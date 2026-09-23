package org.ungoogled.patches.maps.location

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode

/**
 * The platform-provider mode dispatcher's GPS-only branch: start the GPS
 * listener, then stop the network one. This exact five-step sequence (j
 * field, x field, the START call on j, k field, the STOP call on k) is what
 * distinguishes this branch from the GPS+network branch, which restarts BOTH
 * j and k, and from the passive-only branch, which starts from l rather than
 * j. Verified unique across all ten dex files.
 */
internal object GpsOnlyModeFingerprint : Fingerprint(
    name = "c",
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", name = "j"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", name = "x", location = MatchAfterImmediately()),
        methodCall(definingClass = "Laiqk;", name = "b", opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", name = "k", location = MatchAfterImmediately()),
        methodCall(definingClass = "Laiqk;", name = "a", opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
    ),
)
