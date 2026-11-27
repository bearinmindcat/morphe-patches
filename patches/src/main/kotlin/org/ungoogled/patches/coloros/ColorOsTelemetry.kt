package org.ungoogled.patches.coloros

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/**
 * "Remove telemetry" of a ColorOS port. The OPPO statistics SDKs bundled in every ColorOS app (OplusTrack / DCS, DCC
 * track, DRS track) hand their events to ColorOS's statistics service, which uploads them to OPPO. Elsewhere that
 * service is missing and the events go nowhere; on an OPPO / OnePlus / realme phone (issues #106, #113, #155) it is
 * there. The port's runtime ([runtime], runtime/ColorOS.java) already carries every service and provider call the app
 * makes (see treatRefusedComponentsAsMissing): with this patch it answers the statistics service's as if it were missing
 * there too. [more] cuts what an app sends over the network itself.
 */
internal fun colorOsRemoveTelemetryPatch(
    compatibility: Compatibility,
    extension: String,
    runtime: String,
    enablePatch: Patch<*>,
    description: String = "Stops usage statistics to OPPO's statistics service on OPPO, OnePlus and realme phones.",
    more: (BytecodePatchContext.() -> Unit)? = null,
): BytecodePatch = bytecodePatch(
    name = "Remove telemetry",
    description = description,
    default = true,
) {
    compatibleWith(compatibility)
    dependsOn(enablePatch)
    extendWith(extension)

    execute {
        val switch = mutableClassDefByOrNull(runtime)?.methods?.firstOrNull {
            it.name == "statisticsOff" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        } ?: throw PatchException("$runtime has no statisticsOff()")
        switch.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        more?.invoke(this)
    }
}

private const val PROXY_RECORDER = "Lcom/oplus/statistics/record/ProxyRecorder;"

/**
 * For a port without the runtime's service and provider calls (the launchers): OplusTrack's ProxyRecorder
 * addTrackEvent(Context, TrackEvent), where every event is handed to ColorOS's statistics provider (or the older
 * com.nearme.statistics.rom service), returns at once. The SDK keeps its class and method names.
 */
internal fun BytecodePatchContext.dropOplusTrackEvents() {
    val method = mutableClassDefByOrNull(PROXY_RECORDER)?.methods?.firstOrNull {
        it.name == "addTrackEvent" && it.returnType == "V" &&
            it.parameterTypes.joinToString("") == "Landroid/content/Context;Lcom/oplus/statistics/data/TrackEvent;"
    } ?: throw PatchException("OplusTrack's ProxyRecorder.addTrackEvent not found")
    method.addInstructions(0, "return-void")
}
