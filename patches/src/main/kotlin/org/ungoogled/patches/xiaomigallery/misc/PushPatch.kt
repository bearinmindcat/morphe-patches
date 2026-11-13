package org.ungoogled.patches.xiaomigallery.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

/** GalleryPushManager.registerPush(Context): registers with Xiaomi's push service at start-up. */
private object PushRegistrationFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("Register push failed: privacy agreement disabled"),
)

/**
 * Gallery registers with Xiaomi's push service (MiCloud album pushes) at every start. Off MIUI the push library refuses
 * without a region, so it never connected; now it is not asked at all.
 */
internal val pushPatch = bytecodePatch(
    description = "Doesn't register with Xiaomi's push service.",
) {
    execute {
        PushRegistrationFingerprint.method.addInstruction(0, "return-void")
    }
}
