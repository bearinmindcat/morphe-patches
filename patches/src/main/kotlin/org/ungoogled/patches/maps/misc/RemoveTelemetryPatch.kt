package org.ungoogled.patches.maps.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val removeTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "Points the Firebase Installations and Play services compliance check-ins at " +
        "an unresolvable host, stops every ad impression and click ping from being sent, and " +
        "deregisters Google's logging, performance-monitoring, survey and Location History libraries " +
        "and the on-device federated-learning services.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(telemetryRegistrationsPatch)

    execute {
        // Both check-ins are configured in exactly one place each, by loading a
        // host name as a string constant. Repointing that constant at the
        // reserved .invalid TLD (RFC 2606, guaranteed never to resolve) makes
        // every call fail with UnknownHostException — a path both SDKs already
        // handle, because it is what an offline device does.
        //
        // Blocking is deliberately done here rather than by removing the call
        // sites: the callers retry and cache, so a host that cannot resolve is
        // both simpler and closer to what the SDKs expect than a missing method.
        listOf(
            FirebaseInstallationsHostFingerprint to "firebaseinstallations.invalid",
            GmsComplianceHostFingerprint to "gmscompliance-pa.invalid",
        ).forEach { (fingerprint, unresolvableHost) ->
            val index = fingerprint.instructionMatches.first().index
            // Reuse whatever register the original const-string wrote to, so the
            // surrounding code is untouched.
            val register = fingerprint.method
                .getInstruction<OneRegisterInstruction>(index).registerA

            fingerprint.method.replaceInstruction(
                index,
                "const-string v$register, \"$unresolvableHost\"",
            )
        }

        // Ad beacons. Every impression and click ping goes through two private
        // senders on one class; returning at the top of both drops them all.
        // The sponsored rows still render and their Call / Directions / Website
        // actions still work, because those are separate intents, not pings.
        val senders = AdPingSenderFingerprint.classDef.methods.filter { method ->
            AccessFlags.PRIVATE.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                method.implementation?.instructions?.any { instruction ->
                    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == "Landroid/webkit/URLUtil;" && call.name == "isNetworkUrl"
                } == true
        }
        if (senders.size != 2) throw PatchException("expected 2 ad-ping senders, found ${senders.size}")
        senders.forEach { it.addInstruction(0, "return-void") }

        // Promoted-content impressions (sponsored rows, promoted hotels) have a
        // path of their own that builds the www.google.com/adview URL first.
        // Returning at entry, before its monitor-enter, drops only the beacon.
        AdViewImpressionFingerprint.method.addInstruction(0, "return-void")
    }
}
