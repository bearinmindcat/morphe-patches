package org.ungoogled.patches.xiaomilauncher.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** SafeModeOneTrack's Global set-up: a Pub/Sub tracker with Xiaomi's privacy policy taken as accepted. */
private object SafeModePubSubFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("safemode-425511"),
)

/**
 * After a crash loop the launcher's safe mode reports the rescue to Xiaomi (OneTrack, or Google Cloud Pub/Sub on Global
 * builds) with the privacy policy taken as accepted. It only starts after HyperOS's rescue party; now the trackers are
 * never created, so nothing is sent either way.
 */
internal val safeModeReportPatch = bytecodePatch(
    description = "No safe-mode crash report to Xiaomi.",
) {
    execute {
        val tracker = mutableClassDefBy(SafeModePubSubFingerprint.classDef.type)
        // Both set-ups: OneTrack.createInstance (China) and PubSubTrack.createInstance (Global).
        val setups = tracker.methods.filter { m ->
            m.returnType == "V" && m.implementation?.instructions?.any {
                val r = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_STATIC && r?.name == "createInstance"
            } == true
        }
        // Measured against 7.50.06.2372: initOneTrack and initPubSub.
        if (setups.size != 2) throw PatchException("expected 2 safe-mode tracker set-ups, found ${setups.size}")
        setups.forEach { it.addInstruction(0, "return-void") }
    }
}
