package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import org.ungoogled.patches.waze.WazeConstants.COMPATIBILITY_WAZE
import org.w3c.dom.Element

/** Turns Crashlytics on for Waze and names the crash reports: user ID, installation ID, app and build type. */
private object CrashlyticsSwitchOnFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("BUILD_TYPE"), string("USER_FLAG")),
)

/** Waze's usage-stats reporter: every stat, signed-out stat and ad event passes through here on its way out. */
private object StatsDispatchFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("Not reporting the following stat because user is not authenticated: ")),
)

private const val CLIENT_ENG_STATS = "Lcom/waze/stats/ClientEngStatsEventName;"
private const val METRICS_REPORTER = "Lcom/waze/stats/MetricsReporterNativeManager;"

/** Firebase Performance Monitoring has no switch of its own in the manifest; this one turns it off for good. */
private val performanceMonitoringOffPatch = resourcePatch(description = "Deactivates Firebase Performance Monitoring.") {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("meta-data").apply {
                    setAttribute("android:name", "firebase_performance_collection_deactivated")
                    setAttribute("android:value", "true")
                },
            )
        }
    }
}

@Suppress("unused")
val wazeRemoveTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "No crash reports, usage stats or performance data. Traffic reporting stays.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_WAZE)
    dependsOn(wazeConfigOverridesPatch, performanceMonitoringOffPatch)

    execute {
        /** Returns at entry while Remove telemetry is switched on; v0 is a free local in each (checked). */
        fun MutableMethod.skipWhileOn(what: String) {
            if (implementation!!.registerCount - parameterTypes.size - 1 < 1) throw PatchException("$what has no free register")
            addInstructionsWithLabels(
                0,
                """
                    const-string v0, "removeTelemetry"
                    invoke-static { v0 }, $WAZE_CUSTOMIZATION->on(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :telemetry_on
                    return-void
                """,
                ExternalLabel("telemetry_on", implementation!!.instructions.first()),
            )
        }

        // Crashlytics: off in Waze's manifest, switched on by Waze at start-up together with the user's ID.
        // Never switched on, it keeps no crash report to send.
        CrashlyticsSwitchOnFingerprint.method.skipWhileOn("Crashlytics switch")

        // Waze's own usage stats and client engineering stats, and its metrics -- analytics events only. Live
        // traffic, speed and route data travel separately, through Waze's real-time connection, untouched.
        val stats = mutableClassDefBy(StatsDispatchFingerprint.originalClassDef.type)
        StatsDispatchFingerprint.method.skipWhileOn("stats reporter")
        stats.methods.filter { it.returnType == "V" && it.parameterTypes.firstOrNull()?.toString() == CLIENT_ENG_STATS }
            .ifEmpty { throw PatchException("client engineering stats not found in ${stats.type}") }
            .forEach { it.skipWhileOn("client engineering stats") }
        mutableClassDefBy(METRICS_REPORTER).methods.filter { it.name.startsWith("reportMetric") && it.returnType == "V" }
            .ifEmpty { throw PatchException("metrics reporter not found") }
            .forEach { it.skipWhileOn("metrics reporter") }

        // The same, kept off whatever Waze's server says after each sync.
        for (name in listOf(
            "CONFIG_VALUE_GENERAL_ANDROID_CRASHLYTICS_ENABLED",
            "CONFIG_VALUE_GENERAL_CRASHLYTICS_LOG_ENABLED",
            "CONFIG_VALUE_ANALYTICS_FIREBASE_PERFORMANCE_ENABLED",
            "CONFIG_VALUE_ANALYTICS_ANDROID_MAIN_THREAD_RESPONSIVENESS_MONITORING",
            "CONFIG_VALUE_ANALYTICS_ANDROID_CPP_THREAD_RESPONSIVENESS_MONITORING",
            "CONFIG_VALUE_MAP_PERFORMANCE_FRAME_RATE_MEASURE_ENABLED",
            "CONFIG_VALUE_MAP_PERFORMANCE_OBJECT_DENSITY_SAMPLER_ENABLED",
            "CONFIG_VALUE_MAP_PERFORMANCE_OBJECT_SNAPSHOT_SAMPLER_ENABLED",
            "CONFIG_VALUE_CONFIG_REPORT_PREFERENCES_STAT_ENABLED",
            "CONFIG_VALUE_ANDROID_AUTO_LOG_ON_GET_TEMPLATE_DELAY_TO_FIREBASE",
            "CONFIG_VALUE_GOOGLE_ADS_TRUSTLESS_TOKEN_LOGGING_ENABLED",
            "CONFIG_VALUE_SIGNUP_STATS_UNAUTHENTICATED_MODULE_ON",
        )) keepWazeConfig("removeTelemetry", name, false)
    }
}
