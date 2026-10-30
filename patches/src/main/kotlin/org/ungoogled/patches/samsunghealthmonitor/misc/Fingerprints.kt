package org.ungoogled.patches.samsunghealthmonitor.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/** The launcher activity's start: it closes at once unless Build.MANUFACTURER contains "samsung". */
internal object LauncherMakerCheckFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        string("[PERF] onCreate - start"),
        fieldAccess(opcode = Opcode.SGET_OBJECT, definingClass = "Landroid/os/Build;", name = "MANUFACTURER"),
        string("samsung"),
    ),
)

/**
 * The rooting check every screen runs at start; its last part compares the app's own signing certificate with
 * Samsung's two (SHA-1), so any re-signed copy reads as "rooted".
 */
internal object OwnSignatureCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        string("S HealthMonitor - PackageValidator"),
        string("9741A0F330DC2E8619B76A2597F308C37DBE30A2"),
        methodCall(definingClass = "Ljava/util/stream/Stream;", name = "anyMatch"),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
    ),
)

/** The CSC country: One UI's SemSystemProperties.getCountryIso(), then ro.csc.countryiso_code; "" elsewhere. */
internal object CscCountryFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("android.os.SemSystemProperties"),
        string("getCountryIso"),
        string("ro.csc.countryiso_code"),
    ),
)
