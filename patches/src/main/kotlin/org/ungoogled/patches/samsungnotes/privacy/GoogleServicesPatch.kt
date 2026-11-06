package org.ungoogled.patches.samsungnotes.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsungnotes.SamsungNotesConstants.COMPATIBILITY_SAMSUNG_NOTES
import org.ungoogled.patches.samsungnotes.misc.SAMSUNG_NOTES_EXTENSION
import org.w3c.dom.Element

private const val GOOGLE = "Lorg/ungoogled/samsungnotes/privacy/GoogleServices;"

/** Notes' early sync start-up, which starts Firebase at every launch. */
private object SyncQuickInitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("[PUSH] fail to initialize the firebase : ")),
)

/** Samsung sync setting up its pushes (SMP over FCM) after sign-in. */
private object SmpInitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("[PUSH] initializeSmp() start")),
)

/** The Play Store review prompt after some launches. */
private object InAppReviewFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("requestInAppReview# not enough conditions")),
)

/** Google Meet live sharing ("is it supported here?"), checked when the notes list and the editor open. */
private object LiveSharingSupportedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    custom = { method, classDef ->
        method.name == "isSupported" && classDef.type.endsWith("/livesharing/LiveSharingClientWrapper;")
    },
)

/** Google Sign-In's screen and revocation service (unused by Notes); FCM no longer hands notifications to Play services. */
private val googleManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            var disabled = 0
            for (tag in listOf("activity", "service")) {
                val list = manifest.getElementsByTagName(tag)
                for (i in 0 until list.length) {
                    val e = list.item(i) as Element
                    if (e.getAttribute("android:name") in setOf(
                            "com.google.android.gms.auth.api.signin.internal.SignInHubActivity",
                            "com.google.android.gms.auth.api.signin.RevocationBoundService",
                        )
                    ) {
                        e.setAttribute("android:enabled", "false")
                        disabled++
                    }
                }
            }
            if (disabled != 2) throw PatchException("Google Sign-In components not found ($disabled)")
            val meta = manifest.createElement("meta-data")
            meta.setAttribute("android:name", "firebase_messaging_notification_delegation_enabled")
            meta.setAttribute("android:value", "false")
            application.appendChild(meta)
        }
    }
}

@Suppress("unused")
val removeGoogleServicesPatch = bytecodePatch(
    name = "Remove Google services",
    description = "Starts Firebase only for Samsung Cloud sync and removes the Play review prompt and Google Meet sharing.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_NOTES)
    dependsOn(googleManifestPatch)
    extendWith(SAMSUNG_NOTES_EXTENSION)

    execute {
        // Firebase at start-up only once Samsung sync uses it.
        SyncQuickInitFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val index = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC && ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                    r.definingClass == "Lcom/google/firebase/FirebaseApp;" && r.name == "initializeApp" &&
                        r.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
                }
            }
            if (index < 0) throw PatchException("Firebase start-up not found")
            if (instructions[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT) throw PatchException("Firebase start-up result is used")
            val context = (instructions[index] as FiveRegisterInstruction).registerC
            replaceInstruction(index, "invoke-static { v$context }, $GOOGLE->firebaseAtStart(Landroid/content/Context;)V")
        }
        // ... and when sync sets up its pushes (the first time after signing in).
        SmpInitFingerprint.method.addInstructions(0, "invoke-static { p1 }, $GOOGLE->firebase(Landroid/content/Context;)V")

        InAppReviewFingerprint.method.addInstructions(0, "return-void")
        LiveSharingSupportedFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
    }
}
