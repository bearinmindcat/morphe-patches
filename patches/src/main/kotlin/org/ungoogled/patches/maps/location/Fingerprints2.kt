package org.ungoogled.patches.maps.location

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * The fused (Play services) location provider strategy's availability check.
 * `Laije;->e()` picks the first provider strategy that claims availability,
 * so whether this returns true decides whether Maps uses the fused provider
 * or falls through to `Laiql;`, the platform LocationManager strategy.
 */
internal object FusedLocationAvailabilityFingerprint : Fingerprint(
    name = "j",
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_BOOLEAN, definingClass = "this", name = "f"),
        opcode(Opcode.RETURN, MatchAfterImmediately()),
    ),
)

/**
 * Where every Play services connection Maps opens gets its descriptor: the
 * bundled client's getServiceStartIntent-style package (A()) and service
 * action (d()) go straight into the connection config's constructor. The one
 * place that decides which package a location service bind goes to.
 */
internal object PlayServicesConnectionFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("I", "Landroid/os/IInterface;", "I"),
    filters = listOf(
        methodCall(definingClass = "this", name = "A", returnType = "Ljava/lang/String;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "this", name = "d", returnType = "Ljava/lang/String;", location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
        methodCall(definingClass = "this", name = "f", returnType = "Z", location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>",
            parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z"),
            location = MatchAfterImmediately(),
        ),
    ),
)
