package org.ungoogled.patches.samsungnotes.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.hideDiagnosticsAgent
import org.ungoogled.patches.samsung.muteFeatureSurveys
import org.ungoogled.patches.samsungnotes.SamsungNotesConstants.COMPATIBILITY_SAMSUNG_NOTES

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Turns off Samsung Analytics, crash reports, usage surveys and marketing data.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_NOTES)

    execute {
        val log = java.util.logging.Logger.getLogger("SamsungNotes")

        // Samsung Analytics: the tracker is never ready, so it neither starts nor sends.
        AnalyticsTrackerReadyFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        // DiagMon (crash, ANR and performance reports): the diagnostics agent reads as not installed, the SDK's own
        // "not supported" case, so it is never configured: no crash handler, no report.
        val lookups = hideDiagnosticsAgent()
        // Measured against 4.4.48.15: 3 (the SDK's two version checks and its crash handler's).
        if (lookups < 2) throw PatchException("only $lookups diagnostics agent lookups found")

        // Feature-usage surveys to One UI's context service from handwriting recognition (GSIM), the S Pen SDK and
        // multi-window.
        val surveys = muteFeatureSurveys()
        // Measured against 4.4.48.15: 7 senders.
        if (surveys < 3) throw PatchException("only $surveys feature survey senders found")

        // Samsung Marketing Platform (SMP) carries Notes' Samsung Cloud sync pushes (over FCM or Samsung Push), set up
        // once a Samsung account syncs; it also uploads device data, time spent per screen, marketing consent and
        // marketing feedback. It now runs as it does on non-Samsung phones, plain push only: sync pushes still arrive,
        // nothing is uploaded and no marketing is shown.
        val upload = SmpClientsUploadFingerprint.method
        val check = upload.implementation!!.instructions.firstNotNullOfOrNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                insn.opcode == Opcode.INVOKE_STATIC && it.returnType == "Z" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
            }
        } ?: throw PatchException("SMP's plain push check not found in its clients upload")
        mutableClassDefBy(check.definingClass).methods.single {
            it.name == check.name && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
        }.addInstructions(0, "const/4 v0, 0x1\nreturn v0")

        log.info("Diagnostics agent lookups: $lookups, feature survey senders: $surveys")
    }
}
