package org.ungoogled.patches.nothinggallery.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/** Google's data transport for ML Kit's usage logs ("FIREBASE_ML_SDK", the app's only data transport). */
private object MlKitTransportFingerprint : Fingerprint(
    name = "<init>",
    strings = listOf("FIREBASE_ML_SDK"),
)

/**
 * Gallery's background photo analysis (scene labels, subject cut-outs) uses Google's ML Kit on the phone, and ML Kit
 * logs every use and sends those logs to Google (firebaselogging.googleapis.com) -- from the first start, with no
 * question asked. The logs are dropped; the analysis itself stays on the phone and keeps working.
 */
internal val mlKitLogsPatch = bytecodePatch(
    description = "Doesn't send ML Kit's usage logs to Google.",
) {
    execute {
        val transport = mutableClassDefBy(MlKitTransportFingerprint.classDef.type)
        // The transport's send(event): its only other method.
        val sends = transport.methods.filter { it.name != "<init>" && it.returnType == "V" && it.parameterTypes.size == 1 }
        if (sends.size != 1) throw PatchException("expected one send in ML Kit's log transport, found ${sends.size}")
        sends.single().addInstruction(0, "return-void")
    }
}
