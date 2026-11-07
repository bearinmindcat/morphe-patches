package org.ungoogled.patches.samsunginternet

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsung.hideDiagnosticsAgent
import org.ungoogled.patches.samsung.muteFeatureSurveys
import org.ungoogled.patches.samsunginternet.SamsungInternetConstants.COMPATIBILITY_SAMSUNG_INTERNET

private const val SI_LOG = "${SBROWSER}si_log/SILog;"
private const val SI_LOG_CLIENT = "${SBROWSER}si_log/model/client/SILogClient;"
private const val ACTIVATED_TASK = "${SBROWSER}common/tnc/SIActivatedLoggingTask;"
private const val GENERAL_CALLBACK = "${SBROWSER}common/model/general/GeneralCallback;"
private const val COMMAND_LINE = "${SBROWSER}init/SBrowserCommandLine;"

private val log = java.util.logging.Logger.getLogger("SamsungInternet")

/** A Kotlin suspend function that returns Unit: answers the same Unit (read from its own last return) at once. */
private fun MutableMethod.returnUnit() {
    val unit = implementation!!.instructions.lastOrNull { it.opcode == Opcode.SGET_OBJECT }
        ?.let { (it as ReferenceInstruction).reference as FieldReference }
        ?: throw PatchException("$name: no Unit to return")
    addInstructions(0, "sget-object p0, $unit\nreturn-object p0")
}

/**
 * Samsung Internet's own usage log ("SI log": events and settings states with an install ID, to
 * sia.internet.apps.samsung.com), measured sending before the terms are even accepted; the terms-acceptance record
 * (terms.internet.apps.samsung.com/activated with an install ID, model, country and versions); on Galaxy phones also
 * Samsung Analytics, DiagMon crash and ANR reports and One UI's feature-usage surveys; and Chromium's autofill
 * crowdsourcing (form signatures to content-autofill.googleapis.com). Not touched, because they are features: ad
 * blocker lists, Samsung Cloud sync and its push (SMP / Firebase Messaging), Safe Browsing, update checks, the remote
 * configuration, news, and Smart shopping (Kindred, its own setting).
 */
@Suppress("unused")
val removeTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "Stops Samsung's usage logs, analytics, crash reports and autofill crowdsourcing.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_INTERNET)

    execute {
        // SI log: nothing is recorded (events and status logs), and the client never sends what an older install
        // already queued.
        method(SI_LOG, "sendInternal", "I", "Ljava/lang/String;", "I", "Ljava/lang/String;", "Ljava/util/Map;", "${SI_LOG.dropLast(1)}\$ResultListener;").returnEarly()
        method(SI_LOG, "sendStatusLogInternal", "I", "I", "Ljava/lang/String;", "Ljava/lang/String;", "${SI_LOG.dropLast(1)}\$ExtraParameter;").returnEarly()
        method(SI_LOG, "sendStatusLogInternal", "I", "I", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/Map;").returnEarly()
        val client = mutableClassDefByOrNull(SI_LOG_CLIENT) ?: throw PatchException("SILogClient not found")
        val sends = client.methods.filter { it.name == "send" || it.name == "sendBatch" }
        if (sends.size != 2) throw PatchException("SILogClient: expected send and sendBatch, found ${sends.map { it.name }}")
        sends.forEach { it.returnUnit() }

        // Terms-acceptance record (and its withdrawal): reported to the caller as sent.
        method(ACTIVATED_TASK, "onStartTask", "Ljava/lang/String;", GENERAL_CALLBACK).addInstructions(
            0,
            """
                sget-object p1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                invoke-interface {p2, p1}, $GENERAL_CALLBACK->onCallback(Ljava/lang/Object;)V
                return-void
            """,
        )

        // Samsung Analytics (Galaxy phones only: survey mode on, not a "GED" phone).
        method("${SBROWSER}common/device/DeviceSettings;", "getEnableSALogging").returnEarly()

        // DiagMon: the diagnostics agent reads as not installed (the SDK's own "no agent" case).
        val agentLookups = hideDiagnosticsAgent()
        // One UI context framework: feature-usage and status surveys.
        val surveys = muteFeatureSurveys()

        // Chromium's autofill server: form signatures for crowdsourced field predictions. Local autofill stays.
        val initialize = method(COMMAND_LINE, "initialize")
        val switches = initialize.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }
        if ("enable-safe-browsing" !in switches) throw PatchException("SBrowserCommandLine.initialize no longer builds the command line")
        initialize.addInstructions(
            0,
            """
                const-string v0, "disable-features"
                const-string v1, "AutofillServerCommunication"
                invoke-static {v0, v1}, Lcom/sec/terrace/TerraceCommandLine;->appendSwitchWithValue(Ljava/lang/String;Ljava/lang/String;)V
            """,
        )
        log.info("Telemetry: SI log, terms record, Samsung Analytics, $agentLookups DiagMon lookups, $surveys surveys, autofill server")
    }
}
