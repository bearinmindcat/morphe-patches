package org.ungoogled.patches.maps.location

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
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

/**
 * The heading's choice of source (`Laipl;->o()`, issue #41): true sends Maps' heading -- the blue dot's
 * beam, compass mode, the calibration prompt -- to Play services' fused orientation provider, false keeps
 * it on Maps' own fusion of the phone's sensors. True when Play services looks available (which Bypass
 * Play Services checks always makes it) and the phone has an accelerometer, a gyroscope (4 or 16) and a
 * magnetometer (2 or 14). Its class holds the provider's DeviceOrientationListener.
 */
internal object FusedOrientationCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(
        methodCall(definingClass = "Landroid/hardware/SensorManager;", name = "getDefaultSensor"),
        literal(16),
        literal(14),
    ),
    custom = { _, classDef ->
        classDef.fields.any { it.type == "Lcom/google/android/gms/location/DeviceOrientationListener;" }
    },
)

/**
 * Maps' location-settings check (`Lamch;->c(ZZZ Lamck;)V`, issue #40): stores the request's flags, then asks
 * Play services' SettingsClient whether the location settings are right and waits for the answer -- a tap on
 * the my-location button only centres the map once it has arrived.
 */
internal object LocationSettingsCheckFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        methodCall(definingClass = "Lcom/google/android/gms/location/LocationServices;", name = "getSettingsClient"),
    ),
    custom = { method, _ -> method.parameterTypes.size == 4 && method.parameterTypes.take(3).all { it.toString() == "Z" } },
)
