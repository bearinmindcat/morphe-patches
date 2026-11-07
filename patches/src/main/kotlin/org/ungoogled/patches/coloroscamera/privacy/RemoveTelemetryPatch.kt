package org.ungoogled.patches.coloroscamera.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.coloroscamera.ColorOsCameraConstants.COMPATIBILITY_COLOROS_CAMERA

/**
 * OplusTrack's ProxyRecorder (Context, event): the one place OPPO's statistics SDK hands an event over. It inserts it
 * into ColorOS's statistics provider (content://com.oplus.statistics.provider, the "User Experience Program" app
 * com.oplus.statistics.rom, which uploads to OPPO), or without that provider starts the older
 * com.nearme.statistics.rom service with it. Every camera event goes this way: the DcsReporter usage events (modes,
 * settings, zoom, capture counts, focus touch positions, battery drain and temperatures per session, launch and
 * jank timings), the SDK's page visits and app starts, and the crashes its exception handler records.
 */
private object StatisticsRecorderFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("content://com.oplus.statistics.provider/support"),
    custom = { method, _ ->
        method.parameterTypes.size == 2 && method.parameterTypes[0] == "Landroid/content/Context;"
    },
)

/**
 * ColorOS's log service com.oplus.olc ("OLC", log core), an AIDL interface. The camera's AutoLogManager reports its
 * exceptions to it (shutter not responding, lost pictures, thumbnail, preview, video and processing errors) for the
 * system to collect the logs and send them back to OPPO. Its one data call is sendException(JIIIJString)I (the AIDL
 * name is kept in 6.0x builds, renamed in 5.020), made from one place.
 */
private const val LOG_CORE = "Lcom/oplus/olc/IOplusLogCore;"

@Suppress("unused")
val colorOsCameraRemoveTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "Stops usage statistics and automatic log reports to OPPO. Root (mount) install only.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_CAMERA)

    execute {
        // Nothing is handed to the statistics provider or service any more. The events are still built (they are
        // cheap and some feed the app's own logs); they just end here.
        StatisticsRecorderFingerprint.method.addInstructions(0, "return-void")

        // The exception reports to the log service are dropped where they are sent: the call becomes a "0" result
        // (it returns an int the camera only logs). The service connection itself carries nothing and stays.
        val reports = mutableListOf<Pair<String, MethodReference>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lcom/oplus/olc/") || classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                method.implementation?.instructions?.forEach { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEach
                    if (ref.definingClass == LOG_CORE && ref.returnType == "I" && ref.parameterTypes.firstOrNull()?.toString() == "J") {
                        reports += classDef.type to ref
                    }
                }
            }
        }
        if (reports.size != 1) throw PatchException("expected 1 call reporting to the log service, found ${reports.size}")
        val (type, report) = reports.single()
        mutableClassDefBy(type).methods.forEach { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@forEach
            val index = instructions.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference) == report }
            if (index < 0) return@forEach
            val result = instructions.getOrNull(index + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT } as? OneRegisterInstruction
            if (result != null) {
                // replaceInstruction keeps labels (and the try range starting on the call).
                method.replaceInstruction(index, "const/16 v${result.registerA}, 0x0")
                method.replaceInstruction(index + 1, "nop")
            } else {
                method.replaceInstruction(index, "nop")
            }
        }
    }
}
