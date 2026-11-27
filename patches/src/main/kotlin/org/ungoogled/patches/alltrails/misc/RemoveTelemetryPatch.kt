package org.ungoogled.patches.alltrails.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_ALLTRAILS
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.ungoogled.patches.shared.replaceBlockAtLabel
import org.w3c.dom.Element

private const val TELEMETRY = "Lorg/ungoogled/alltrails/Telemetry;"
private const val APPLICATION = "Lcom/alltrails/alltrails/app/AllTrailsApplication;"
private const val CRASHLYTICS = "Lcom/google/firebase/crashlytics/FirebaseCrashlytics;"
private const val EMBRACE = "Lio/embrace/android/embracesdk/Embrace;"
private const val SPRIG = "Lcom/userleap/Sprig;"
private const val USERLEAP = "Lcom/userleap/UserLeap;"
private const val APPSFLYER = "Lcom/appsflyer/AppsFlyerLib;"
private const val BRAZE_COMPANION = "Lcom/braze/Braze\$Companion;"
private const val INSIGHTS_EVENTS = "Lcom/algolia/client/model/insights/InsightsEvents;"

/** Firebase's own switches: Analytics, Crashlytics, Performance and Sessions off; no advertising ID. */
private val TELEMETRY_METADATA = listOf(
    "firebase_analytics_collection_deactivated" to "true",
    "google_analytics_adid_collection_enabled" to "false",
    "firebase_crashlytics_collection_enabled" to "false",
    "firebase_performance_collection_deactivated" to "true",
    "firebase_sessions_enabled" to "false",
)

/** The advertising ID and Android's ad-attribution APIs. Ads themselves are left as they are. */
private val AD_TRACKING_PERMISSIONS = setOf(
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_TOPICS",
)

private val telemetryManifestPatch = resourcePatch(
    description = "Turns Firebase's collection off and drops the advertising ID permissions.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val metas = manifest.getElementsByTagName("meta-data").let { list -> (0 until list.length).map { list.item(it) as Element } }
                .filter { it.parentNode == application }
            for ((name, value) in TELEMETRY_METADATA) {
                val meta = metas.firstOrNull { it.getAttribute("android:name") == name }
                    ?: manifest.createElement("meta-data").also {
                        it.setAttribute("android:name", name)
                        application.appendChild(it)
                    }
                meta.setAttribute("android:value", value)
            }

            val permissions = manifest.getElementsByTagName("uses-permission").let { list -> (0 until list.length).map { list.item(it) as Element } }
            val removed = permissions.filter { it.getAttribute("android:name") in AD_TRACKING_PERMISSIONS }
            if (removed.isEmpty()) throw PatchException("AllTrails no longer asks for the advertising ID")
            removed.forEach { it.parentNode.removeChild(it) }
        }
    }
}

/** AllTrailsApplication.onCreate: the SDKs are switched off right after super.onCreate. */
private object TelemetryApplicationOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
    custom = { _, classDef -> classDef.type == APPLICATION },
)

/** Amplitude's track step, which drops every event while the configuration says opt out. */
private object AmplitudeOptOutCheckFingerprint : Fingerprint(
    strings = listOf("Skip event for opt out config."),
)

/** Branch's TrackingController.disableTracking(context, disable, callback). */
private object BranchDisableTrackingFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("disableTracking context: "),
)

/** AllTrails' Algolia service: sends search click and conversion events to Algolia Insights. */
private object AlgoliaInsightsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(INSIGHTS_EVENTS, "Ljava/lang/String;"),
    custom = { _, classDef -> !classDef.type.startsWith("Lcom/algolia/") },
)

private fun MutableMethod.returnAtEntry() = replaceBlockAtLabel(0, if (returnType == "V") "return-void" else throw PatchException("$name returns $returnType"))

/** The methods with code of [type] called [name] whose parameters [match]; none when the class is missing. */
private fun BytecodePatchContext.methodsOrNone(type: String, name: String, match: (List<String>) -> Boolean): List<MutableMethod> =
    mutableClassDefByOrNull(type)?.methods?.filter {
        it.name == name && it.implementation != null && match(it.parameterTypes.map(CharSequence::toString))
    } ?: emptyList()

/** As [methodsOrNone], but at least one, or the patch fails. */
private fun BytecodePatchContext.methods(type: String, name: String, match: (List<String>) -> Boolean): List<MutableMethod> =
    methodsOrNone(type, name, match).ifEmpty { throw PatchException("$type.$name not found") }

/**
 * AllTrails starts AppsFlyer, Amplitude, Branch, Braze, Embrace, Sprig and
 * Firebase Analytics, Crashlytics, Performance and Sessions as it opens, signed
 * in or not, and only holds them back on a satellite connection. This switches
 * them off in the app (their servers are not blocked by name):
 *
 * - Firebase: the manifest switches above (Analytics deactivated, Crashlytics,
 *   Performance and Sessions off). AllTrails turns Crashlytics on in code at every
 *   start, so FirebaseCrashlytics' switch is held off and its "send unsent
 *   reports" deletes them instead.
 * - AppsFlyer (install attribution): never initialised or started; it logs nothing.
 * - Amplitude (product analytics): opted out; Amplitude Experiment's feature
 *   flags, which decide how the app behaves, still load.
 * - Branch (attribution): tracking disabled; it then only answers Branch links.
 * - Braze (engagement): offline, unless the option keeps it.
 * - Embrace (session telemetry) and Sprig (surveys and session replay): never started.
 * - Mapbox: its own map-event telemetry switch, off.
 * - Algolia Insights: AllTrails' search click events are not sent; search itself is untouched.
 * - The advertising ID permissions are removed. Ads are not touched.
 */
@Suppress("unused")
val removeTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "No analytics, attribution, crash reports, surveys or session replay.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ALLTRAILS)
    dependsOn(telemetryManifestPatch)
    extendWith("extensions/alltrails.mpe")

    val keepBraze = booleanOption(
        key = "keepBraze",
        default = false,
        title = "Keep push notifications",
        description = "Leaves Braze, which sends AllTrails' push notifications and messages, online.",
        required = true,
    )

    execute {
        // At start: Braze offline, Mapbox telemetry off (see Telemetry in the extension).
        TelemetryApplicationOnCreateFingerprint.method.apply {
            val superCall = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_SUPER && ((it as ReferenceInstruction).reference as MethodReference).name == "onCreate"
            }
            if (superCall < 0) throw PatchException("AllTrailsApplication.onCreate no longer calls super.onCreate")
            val off = if (keepBraze.value == true) "offKeepBraze" else "off"
            addInstructionsAtLabel(superCall + 1, "invoke-static { p0 }, $TELEMETRY->$off(Landroid/content/Context;)V")
        }

        // Firebase Crashlytics: AllTrails switches collection on in code at every start (the manifest
        // switch only sets the default). Held off; reports kept from before are deleted, not sent.
        methods(CRASHLYTICS, "setCrashlyticsCollectionEnabled") { it == listOf("Z") }
            .forEach { it.addInstructionsAtLabel(0, "const/4 p1, 0x0") }
        methods(CRASHLYTICS, "setCrashlyticsCollectionEnabled") { it == listOf("Ljava/lang/Boolean;") }
            .forEach { it.addInstructionsAtLabel(0, "sget-object p1, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;") }
        methods(CRASHLYTICS, "sendUnsentReports") { it.isEmpty() }.forEach {
            it.replaceBlockAtLabel(0, "invoke-virtual { p0 }, $CRASHLYTICS->deleteUnsentReports()V\nreturn-void")
        }

        // Embrace: never started (AllTrails starts it from its start-up and from Embrace's own entry point).
        methods(EMBRACE, "start") { it.firstOrNull() == "Landroid/content/Context;" }.forEach { it.returnAtEntry() }

        // Sprig: never configured, so no visitor, events, surveys or screen recordings.
        listOf(SPRIG, USERLEAP).flatMap { type -> methodsOrNone(type, "configure") { it.firstOrNull() == "Landroid/content/Context;" } }
            .ifEmpty { throw PatchException("Sprig's configure() not found") }
            .forEach { it.returnAtEntry() }

        // AppsFlyer: its implementation (the one AppsFlyerLib subclass) takes no developer key and starts,
        // logs and resolves nothing, so it never fetches its settings, attributes the install or sends events.
        val appsFlyer = mutableListOf<String>()
        classDefForEach { if (it.superclass == APPSFLYER && !AccessFlags.ABSTRACT.isSet(it.accessFlags)) appsFlyer += it.type }
        val appsFlyerImpl = appsFlyer.singleOrNull() ?: throw PatchException("AppsFlyer's implementation not found: $appsFlyer")
        methods(appsFlyerImpl, "init") { it.size == 3 && it[0] == "Ljava/lang/String;" && it[2] == "Landroid/content/Context;" }
            .forEach { it.replaceBlockAtLabel(0, "return-object p0") }
        methods(appsFlyerImpl, "start") { it.firstOrNull() == "Landroid/content/Context;" }.forEach { it.returnAtEntry() }
        listOf("logEvent", "logLocation", "logSession", "performOnAppAttribution", "performOnDeepLinking", "sendPushNotificationData")
            .flatMap { name -> methodsOrNone(appsFlyerImpl, name) { true }.filter { it.returnType == "V" } }
            .forEach { it.returnAtEntry() }

        // Amplitude: the configuration's optOut getter answers true, so track() drops every event.
        // R8 renamed the getter; it is the no-argument boolean the opt-out check calls before its log line.
        val optOutCheck = AmplitudeOptOutCheckFingerprint.method.implementation!!.instructions
        val skipLog = optOutCheck.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "Skip event for opt out config." }
        val optOut = optOutCheck.take(skipLog).firstNotNullOfOrNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                insn.opcode == Opcode.INVOKE_VIRTUAL && it.returnType == "Z" && it.parameterTypes.isEmpty()
            }
        } ?: throw PatchException("Amplitude's track step no longer asks its configuration about opt out")
        val optOutGetters = mutableListOf<String>()
        classDefForEach { classDef ->
            if ((classDef.type == optOut.definingClass || classDef.superclass == optOut.definingClass) &&
                classDef.methods.any { it.name == optOut.name && it.parameterTypes.isEmpty() && it.returnType == "Z" && it.implementation != null }
            ) optOutGetters += classDef.type
        }
        if (optOutGetters.isEmpty()) throw PatchException("Amplitude's opt-out getter ${optOut.name}() not found in ${optOut.definingClass}")
        optOutGetters.forEach { type ->
            // p0 (this) is not needed once the method returns.
            methods(type, optOut.name) { it.isEmpty() }.forEach { it.replaceBlockAtLabel(0, "const/4 p0, 0x1\nreturn p0") }
        }

        // Branch: tracking stays disabled -- every disableTracking(..., false) becomes true, and the
        // state read back at start-up is true whatever was saved.
        BranchDisableTrackingFingerprint.let { fp ->
            val method = fp.method
            val flag = method.parameterTypes.map(CharSequence::toString).indexOf("Z")
            if (flag < 0) throw PatchException("Branch's disableTracking no longer takes a boolean")
            // An instance method of one-register parameters: p0 is this.
            method.addInstructionsAtLabel(0, "const/4 p${flag + 1}, 0x1")

            val controller = mutableClassDefBy(method.definingClass)
            val state = controller.fields.singleOrNull { it.type == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags) }
                ?: throw PatchException("Branch's tracking controller no longer keeps one boolean")
            val load = controller.methods.singleOrNull { m ->
                m != method && m.returnType == "V" && m.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
                    m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "bnc_tracking_state" } == true
            } ?: throw PatchException("Branch's tracking state loader not found")
            // At each return the Context parameter (p1) is dead: it holds the value to store.
            val returns = load.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            returns.asReversed().forEach { index ->
                load.addInstructionsAtLabel(index, "const/4 p1, 0x1\niput-boolean p1, p0, ${controller.type}->${state.name}:Z")
            }
        }

        // Braze: held offline (AllTrails puts it back online when the network changes).
        if (keepBraze.value != true) {
            methods(BRAZE_COMPANION, "setOutboundNetworkRequestsOffline") { it == listOf("Z") }
                .forEach { it.addInstructionsAtLabel(0, "const/4 p1, 0x1") }
        }

        // Algolia Insights: search click and conversion events.
        AlgoliaInsightsFingerprint.method.returnAtEntry()
    }
}
