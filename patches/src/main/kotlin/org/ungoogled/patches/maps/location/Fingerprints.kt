package org.ungoogled.patches.maps.location

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * The platform (LocationManager) location strategy's constructor: it makes one listener per Android provider,
 * named "gps", "network" and "passive", and keeps the Looper they run on.
 */
internal object PlatformLocationStrategyFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(string("gps"), string("network"), string("passive")),
    custom = { _, classDef -> classDef.fields.any { it.type == "Landroid/os/Looper;" } },
)

/**
 * Its provider-mode dispatcher: switches on the mode's ordinal and starts (`(Looper)V`) or stops (`()V`) the
 * gps / network / passive listeners. The GPS-only branch is told apart from the others in the patch, by the
 * listeners' fields (read from the constructor).
 */
internal object GpsOnlyModeFingerprint : Fingerprint(
    classFingerprint = PlatformLocationStrategyFingerprint,
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(name = "ordinal", returnType = "I"),
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", type = "Landroid/os/Looper;"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf("Landroid/os/Looper;"), returnType = "V"),
    ),
)
