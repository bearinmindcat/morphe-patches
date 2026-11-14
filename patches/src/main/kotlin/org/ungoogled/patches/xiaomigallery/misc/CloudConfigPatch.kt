package org.ungoogled.patches.xiaomigallery.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/** Xiaomi's cloud config SDK (com.xiaomi.teg.config): "may I use the network", a stored switch that defaults to yes. */
private object CloudConfigNetworkFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("pref_network_access_enabled"),
)

/**
 * At every start Gallery's cloud control downloads Xiaomi's remote config (mcc.intl.inf.miui.com, about 50 KB): its
 * network switch follows Gallery's networking agreement, which international builds take as given -- on HyperOS the
 * phone's own terms cover it, elsewhere nobody agreed. The SDK now never uses the network; Gallery keeps its defaults.
 */
internal val cloudConfigPatch = bytecodePatch(
    description = "Doesn't download Xiaomi's cloud config.",
) {
    execute {
        CloudConfigNetworkFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
    }
}
