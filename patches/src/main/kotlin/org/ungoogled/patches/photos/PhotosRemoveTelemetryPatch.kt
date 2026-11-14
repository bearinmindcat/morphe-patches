package org.ungoogled.patches.photos

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.ungoogled.patches.gmail.methodsUsing
import org.ungoogled.patches.gmail.mutable
import org.ungoogled.patches.gmail.single
import org.ungoogled.patches.gmail.stringOrNull
import org.ungoogled.patches.photos.PhotosConstants.COMPATIBILITY_PHOTOS
import org.w3c.dom.Element

/**
 * Google libraries bundled into Photos that register with Play services' flag service (Phenotype) and exist only
 * to report back: logging (Clearcut), performance monitoring (Primes) and surveys -- the Maps and Gmail method.
 */
private val PHENOTYPE_PACKAGES = listOf(
    "com.google.android.gms.clearcut_client",
    "com.google.android.libraries.performance.primes",
    "com.google.android.libraries.surveys",
)

private val PHENOTYPE_KEY = Regex(
    """com\.google\.android\.gms\.phenotype\.(?:heterodyne_info\.binarypb|registration\.binarypb|registration\.xml):(.+)""",
)

/** Primes' crash and memory report sender, and the Play Store install-referrer report. */
private val RECEIVERS = setOf(
    "com.google.android.libraries.performance.primes.transmitter.LifeboatReceiver",
    "com.google.android.apps.photos.analytics.install.InstallReceiver",
)
private const val CRONET_TELEMETRY = "android.net.http.EnableTelemetry"
private const val AD_ID = "com.google.android.gms.permission.AD_ID"
/** Firebase Analytics' own switches: off for good, whatever the app asks later, and no advertising ID. */
private val FIREBASE_OFF = mapOf(
    "firebase_analytics_collection_deactivated" to "true",
    "google_analytics_adid_collection_enabled" to "false",
)

private val telemetryManifestPatch = resourcePatch(
    description = "Deregisters Photos' logging, performance and survey libraries, turns Cronet's telemetry and " +
        "Firebase Analytics off and removes the advertising ID permission.",
) {
    execute {
        var keys = 0
        var receivers = 0
        var permissions = 0
        var cronet = 0
        document("AndroidManifest.xml").use { manifest ->
            fun all(tag: String) = manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            val doomed = all("meta-data").filter { element ->
                PHENOTYPE_KEY.matchEntire(element.getAttribute("android:name"))?.groupValues?.get(1) in PHENOTYPE_PACKAGES
            } + all("receiver").filter { it.getAttribute("android:name") in RECEIVERS } +
                all("uses-permission").filter { it.getAttribute("android:name") == AD_ID }
            for (element in doomed) {
                when (element.tagName) {
                    "meta-data" -> keys++
                    "receiver" -> receivers++
                    else -> permissions++
                }
                element.parentNode.removeChild(element)
            }
            // Cronet, Photos' HTTP engine, reports its own usage while this is true.
            for (element in all("meta-data").filter { it.getAttribute("android:name") == CRONET_TELEMETRY }) {
                element.setAttribute("android:value", "false")
                cronet++
            }
            val application = all("application").single()
            for ((name, value) in FIREBASE_OFF) {
                val element = all("meta-data").firstOrNull { it.getAttribute("android:name") == name }
                    ?: manifest.createElement("meta-data").also { application.appendChild(it); it.setAttribute("android:name", name) }
                element.setAttribute("android:value", value)
            }
        }

        var assets = 0
        for (pkg in PHENOTYPE_PACKAGES) {
            val path = "assets/phenotype/${pkg.replace('.', '_')}_package_metadata.binarypb"
            if (!get(path).exists()) continue
            delete(path)
            assets++
        }

        // Measured against this Photos version: three keys each for Clearcut and Primes, two for surveys (no
        // heterodyne key), one asset per package, one of everything else.
        if (keys != 8 || assets != PHENOTYPE_PACKAGES.size || receivers != RECEIVERS.size || permissions != 1 || cronet != 1) {
            throw PatchException(
                "expected 8 registration keys, ${PHENOTYPE_PACKAGES.size} assets, ${RECEIVERS.size} receivers, 1 ad ID " +
                    "permission and 1 Cronet switch; found $keys, $assets, $receivers, $permissions and $cronet",
            )
        }
    }
}

/** Photos' Firebase Analytics logger: the event it adds to every engagement event. */
private const val CORE_ENGAGEMENT = "core_engagement"
/** The survey service's host (HaTS), and the feedback host it switches to. */
private const val SURVEY_HOST = "scone-pa.googleapis.com"
private const val SURVEY_FEEDBACK_HOST = "feedback-pa.googleapis.com"
/** GrowthKit, the in-app promo library: the line it logs when its start-up fails. */
private const val GROWTHKIT_FAILED = "GrowthKit failed to start."
/** Guava's ImmediateFuture, by the logger name it keeps. */
private const val IMMEDIATE_FUTURE = "com.google.common.util.concurrent.ImmediateFuture"

@Suppress("unused")
val photosRemoveTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "Stops Firebase Analytics, logging, surveys and promo fetches.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PHOTOS)
    dependsOn(telemetryManifestPatch)

    execute {
        val anchors = methodsUsing(setOf(CORE_ENGAGEMENT, SURVEY_HOST, GROWTHKIT_FAILED, IMMEDIATE_FUTURE))

        // 1. Firebase Analytics (tab taps, opened screens, memories viewed, "core engagement"...): Photos' logger
        //    neither records an event, nor starts Firebase Analytics, nor turns its collection on.
        // The logger records it itself (other classes only pass the name in).
        val logger = anchors[CORE_ENGAGEMENT].orEmpty().filter { found ->
            found.method.returnType == "V" && AccessFlags.PRIVATE.isSet(found.method.accessFlags) &&
                found.method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Landroid/os/Bundle;")
        }.map { it.classDef }.distinctBy { it.type }.singleOrNull()
            ?: throw PatchException("Firebase Analytics logger: expected one class recording \"$CORE_ENGAGEMENT\"")
        fun private(vararg parameters: String, public: Boolean = false) = logger.methods.filter { m ->
            m.returnType == "V" && m.parameterTypes.map(CharSequence::toString) == parameters.toList() &&
                AccessFlags.PRIVATE.isSet(m.accessFlags) != public && !AccessFlags.STATIC.isSet(m.accessFlags)
        }
        val record = private("Ljava/lang/String;", "Landroid/os/Bundle;")
        val start = private("I")
        val consent = private("Z", public = true)
        if (record.size != 1 || start.size != 1 || consent.size != 1) {
            throw PatchException("Firebase Analytics logger changed: ${record.size} recorders, ${start.size} starters, ${consent.size} consent switches")
        }
        for (m in record + start + consent) {
            mutableClassDefBy(logger.type).methods.single { it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == "V" }
                .addInstructions(0, "return-void")
        }

        // 2. Surveys: the survey service's host, picked in one place, now never resolves (RFC 2606 .invalid), so
        //    every survey request fails the way it does offline, and no survey is fetched or shown.
        val surveyHost = anchors.single(SURVEY_HOST, "survey host")
        mutable(surveyHost).apply {
            if (returnType != "Ljava/lang/String;" || parameterTypes.isNotEmpty()) throw PatchException("survey host is no longer a ()String getter")
            val instructions = implementation!!.instructions.toList()
            var hosts = 0
            for ((index, insn) in instructions.withIndex()) {
                val string = insn.stringOrNull() ?: continue
                if (string != SURVEY_HOST && string != SURVEY_FEEDBACK_HOST) continue
                replaceInstruction(index, "const-string v${(insn as OneRegisterInstruction).registerA}, \"scone-pa.invalid\"")
                hosts++
            }
            if (hosts != 2) throw PatchException("survey host getter no longer picks between two hosts ($hosts)")
        }

        // 3. GrowthKit, which syncs and shows Google's in-app promos: its start-up is reported done without
        //    starting, so it never syncs a promo and shows none.
        val failed = anchors.single(GROWTHKIT_FAILED, "GrowthKit start-up")
        val code = failed.method.implementation!!.instructions.toList()
        val at = code.indexOfFirst { it.stringOrNull() == GROWTHKIT_FAILED }
        val starter = code.subList(0, at).lastOrNull { it.opcode == Opcode.SGET_OBJECT }
            ?.let { ((it as ReferenceInstruction).reference as FieldReference).definingClass }
            ?: throw PatchException("GrowthKit start-up: its logger is no longer read before the failure line")
        val immediate = anchors[IMMEDIATE_FUTURE].orEmpty().map { it.classDef }.distinctBy { it.type }.singleOrNull()
            ?: throw PatchException("Guava's ImmediateFuture not found")
        val starts = classDefBy(starter).methods.filter {
            it.parameterTypes.isEmpty() && it.returnType.startsWith("L") && !AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.returnType.toString() in immediate.interfaces
        }
        if (starts.isEmpty() || starts.size > 2) throw PatchException("GrowthKit start-up: expected one or two start methods in $starter, found ${starts.size}")
        val future = starts.first().returnType.toString()
        val done = immediate.fields.singleOrNull { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == future }
            ?: throw PatchException("ImmediateFuture no longer keeps its done-with-null instance")
        for (m in starts) {
            mutableClassDefBy(starter).methods.single { it.name == m.name && it.parameterTypes.isEmpty() && it.returnType == m.returnType }.apply {
                if (implementation!!.registerCount < 2) throw PatchException("GrowthKit start has no free register")
                addInstructions(0, "sget-object v0, ${immediate.type}->${done.name}:$future\nreturn-object v0")
            }
        }
    }
}
