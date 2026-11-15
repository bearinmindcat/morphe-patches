package org.ungoogled.patches.maps.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import org.w3c.dom.Element
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.maps.microg.MicrogSelection
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** Firebase Analytics off in the manifest: Maps ships it switched on, and nothing else reads these. */
private val firebaseAnalyticsOffPatch = resourcePatch(
    description = "Turns Firebase Analytics off in the manifest.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val metas = manifest.getElementsByTagName("meta-data").let { n -> (0 until n.length).map { n.item(it) as Element } }
            fun meta(name: String, value: String) {
                val element = metas.firstOrNull { it.getAttribute("android:name") == name }
                    ?: manifest.createElement("meta-data").also {
                        it.setAttribute("android:name", name)
                        application.appendChild(it)
                    }
                element.setAttribute("android:value", value)
            }
            if (metas.none { it.getAttribute("android:name") == "firebase_analytics_collection_enabled" }) {
                throw PatchException("Firebase Analytics is no longer switched on in the manifest")
            }
            meta("firebase_analytics_collection_enabled", "false")
            meta("firebase_analytics_collection_deactivated", "true")
        }
    }
}

@Suppress("unused")
val removeTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "Points the Firebase Installations and Play services compliance check-ins at " +
        "an unresolvable host, turns Firebase Analytics off, stops every ad impression and click ping from being sent, and " +
        "deregisters Google's logging, performance-monitoring, survey and Location History libraries " +
        "and the on-device federated-learning services. With Add microG-RE support, Firebase Installations " +
        "and Location History are left alone, so Timeline, account sync and push messages keep working.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(telemetryRegistrationsPatch, firebaseAnalyticsOffPatch)

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
        //
        // Not Firebase Installations in microG Maps: push messages to a signed-in
        // account (location sharing, for one) need the installation it registers.
        val microg = MicrogSelection.builds(this, "Remove telemetry")
        listOfNotNull(
            if (microg) null else FirebaseInstallationsHostFingerprint to "firebaseinstallations.invalid",
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

        // Firebase Analytics: Maps' events (app open, directions requests, route mode changes...) and the
        // automatic session events go out through Play services' measurement service (measured: session_start
        // uploaded). The manifest switch above stops collection, but a root mount install keeps the stock manifest;
        // so the measurement client also takes the way out it has for an app without FirebaseAnalytics: marked
        // unavailable right after it is made, every later call a no-op, nothing started or registered.
        MeasurementClientFingerprint.method.apply {
            val code = implementation!!.instructions.toList()
            // Its "FirebaseAnalytics not bundled" exit: a boolean of its own set, then return (26.36) or a jump
            // straight to the return (26.44).
            fun returns(next: com.android.tools.smali.dexlib2.iface.instruction.Instruction?): Boolean =
                next?.opcode == Opcode.RETURN_VOID || (next?.opcode in listOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) &&
                    (next as com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction).target.location.instruction?.opcode == Opcode.RETURN_VOID)
            val unavailable = code.indices.firstOrNull { i ->
                code[i].opcode == Opcode.IPUT_BOOLEAN && returns(code.getOrNull(i + 1)) &&
                    ((code[i] as ReferenceInstruction).reference as FieldReference).definingClass == definingClass
            }?.let { (code[it] as ReferenceInstruction).reference as FieldReference }
                ?: throw PatchException("measurement client no longer marks itself unavailable")
            val made = code.indices.firstOrNull { i ->
                code[i].opcode == Opcode.IPUT_OBJECT &&
                    ((code[i] as ReferenceInstruction).reference as FieldReference).type == "Lcom/google/android/gms/measurement/api/AppMeasurementSdk;"
            } ?: throw PatchException("measurement client no longer keeps its AppMeasurementSdk")
            // The register that carried the SDK into its field is free right after.
            val free = (code[made] as TwoRegisterInstruction).registerA
            if (free > 15 || (code[made] as TwoRegisterInstruction).registerB != implementation!!.registerCount - parameterTypes.size - 1) {
                throw PatchException("measurement client's constructor changed shape")
            }
            addInstructions(
                made + 1,
                """
                    const/4 v$free, 0x1
                    iput-boolean v$free, p0, ${unavailable.definingClass}->${unavailable.name}:Z
                    return-void
                """,
            )
        }
    }
}
