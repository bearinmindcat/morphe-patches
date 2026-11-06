package org.ungoogled.patches.gmail

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
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL
import org.ungoogled.patches.gmail.GmailConstants.GMAIL_EXTENSION
import org.w3c.dom.Element

/**
 * Google libraries bundled into Gmail that register with Play services' flag service (Phenotype) and exist only
 * to report back: logging (Clearcut), performance monitoring (Primes), client tracing and surveys. The same
 * method as Maps' Remove telemetry: each registers through three manifest meta-data keys and an asset blob.
 */
private val PHENOTYPE_PACKAGES = listOf(
    "com.google.android.gms.clearcut_client",
    "com.google.android.libraries.performance.primes",
    "clienttracing.android",
    "com.google.android.libraries.surveys",
)

private val PHENOTYPE_KEY = Regex(
    """com\.google\.android\.gms\.phenotype\.(?:heterodyne_info\.binarypb|registration\.binarypb|registration\.xml):(.+)""",
)

/** Primes' crash and memory report sender. */
private const val PRIMES_RECEIVER = "com.google.android.libraries.performance.primes.transmitter.LifeboatReceiver"
private const val CRONET_TELEMETRY = "android.net.http.EnableTelemetry"

/** The advertising ID and Android's ad attribution (Privacy Sandbox). */
private val AD_ID_PERMISSIONS = setOf(
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
)
private const val AD_SERVICES_LIBRARY = "android.ext.adservices"

private val telemetryManifestPatch = resourcePatch(
    description = "Deregisters Gmail's logging, performance, tracing and survey libraries, turns Cronet's " +
        "telemetry off and removes the advertising ID permissions.",
) {
    execute {
        var keys = 0
        var components = 0
        var permissions = 0
        var cronet = 0
        var libraries = 0
        document("AndroidManifest.xml").use { manifest ->
            fun all(tag: String) = manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            val doomed = all("meta-data").filter { element ->
                PHENOTYPE_KEY.matchEntire(element.getAttribute("android:name"))?.groupValues?.get(1) in PHENOTYPE_PACKAGES
            } + all("receiver").filter { it.getAttribute("android:name") == PRIMES_RECEIVER } +
                all("uses-permission").filter { it.getAttribute("android:name") in AD_ID_PERMISSIONS } +
                all("uses-library").filter { it.getAttribute("android:name") == AD_SERVICES_LIBRARY }
            for (element in doomed) {
                when (element.tagName) {
                    "meta-data" -> keys++
                    "receiver" -> components++
                    "uses-library" -> libraries++
                    else -> permissions++
                }
                element.parentNode.removeChild(element)
            }
            // Cronet, Gmail's HTTP engine, reports its own usage while this is true.
            for (element in all("meta-data").filter { it.getAttribute("android:name") == CRONET_TELEMETRY }) {
                element.setAttribute("android:value", "false")
                cronet++
            }
        }

        var assets = 0
        for (pkg in PHENOTYPE_PACKAGES) {
            val path = "assets/phenotype/${pkg.replace('.', '_')}_package_metadata.binarypb"
            if (!get(path).exists()) continue
            delete(path)
            assets++
        }

        // Measured against this Gmail version: three keys per package (surveys has no heterodyne key, so 11), one
        // asset per package, one of everything else.
        if (keys != 3 * PHENOTYPE_PACKAGES.size - 1 || assets != PHENOTYPE_PACKAGES.size || components != 1 ||
            permissions != AD_ID_PERMISSIONS.size || cronet != 1 || libraries != 1
        ) throw PatchException(
            "expected ${3 * PHENOTYPE_PACKAGES.size - 1} registration keys, ${PHENOTYPE_PACKAGES.size} assets, 1 receiver, " +
                "${AD_ID_PERMISSIONS.size} ad ID permissions, 1 Cronet switch and 1 ad services library; found $keys, " +
                "$assets, $components, $permissions, $cronet and $libraries",
        )
    }
}

/** A message's link tap: recorded as an interaction and as a label on the message, then synced to Google. */
private const val LINK_CLICK = "Failed to log interaction of type MESSAGE_LINK_CLICK"
/** The survey service's host (HaTS), and the feedback host it switches to. */
private const val SURVEY_HOST = "scone-pa.googleapis.com"
private const val SURVEY_FEEDBACK_HOST = "feedback-pa.googleapis.com"
/** Google's in-app promotions server, set for Gmail's Chat and Spaces. */
private val PROMO_HOSTS = mapOf(
    "https://appsgrowthpromo-pa.googleapis.com" to "https://appsgrowthpromo-pa.invalid",
    "appsgrowthpromo-pa.googleapis.com" to "appsgrowthpromo-pa.invalid",
)
/** GrowthKit, the in-app promo library: the line it logs when its start-up fails. */
private const val GROWTHKIT_FAILED = "GrowthKit failed to start."
private const val LISTENABLE_FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"

@Suppress("unused")
val gmailRemoveTelemetryPatch = bytecodePatch(
    name = "Remove telemetry",
    description = "Stops logging, surveys, promo fetches and link-click tracking.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GMAIL)
    dependsOn(telemetryManifestPatch)
    extendWith(GMAIL_EXTENSION)

    execute {
        val anchors = methodsUsing(setOf(LINK_CLICK, SURVEY_HOST, GROWTHKIT_FAILED) + PROMO_HOSTS.keys)

        // 1. Link taps in a message: the link still opens; only the recording of the tap is gone.
        val linkClick = anchors.single(LINK_CLICK, "link-click logging")
        mutable(linkClick).apply {
            if (returnType != "V" || parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/String;")) {
                throw PatchException("link-click logging is no longer (String)V")
            }
            addInstructions(0, "return-void")
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

        // 3. Promotions: the in-app promotion server Chat and Spaces use, likewise unresolvable.
        var promos = 0
        for ((host, invalid) in PROMO_HOSTS) {
            for (found in anchors[host].orEmpty()) {
                mutable(found).apply {
                    val instructions = implementation!!.instructions.toList()
                    for ((index, insn) in instructions.withIndex()) {
                        if (insn.stringOrNull() != host) continue
                        val opcode = if (insn.opcode == Opcode.CONST_STRING) "const-string" else "const-string/jumbo"
                        replaceInstruction(index, "$opcode v${(insn as OneRegisterInstruction).registerA}, \"$invalid\"")
                        promos++
                    }
                }
            }
        }
        // Measured: Chat's environment table names it for production and gamma, Gmail's Chat config once.
        if (promos < 2 || PROMO_HOSTS.keys.any { anchors[it].isNullOrEmpty() }) {
            throw PatchException("expected the promotion server in Chat's environments and Gmail's Chat config, found $promos")
        }

        // 4. GrowthKit, which syncs and shows Google's in-app promos (tooltips, dialogs, cards): its start-up is
        //    reported done without starting, so it never syncs a promo and shows none.
        val failed = anchors.single(GROWTHKIT_FAILED, "GrowthKit start-up")
        val code = failed.method.implementation!!.instructions.toList()
        val at = code.indexOfFirst { it.stringOrNull() == GROWTHKIT_FAILED }
        val starter = code.subList(0, at).lastOrNull { it.opcode == Opcode.SGET_OBJECT }
            ?.let { ((it as ReferenceInstruction).reference as FieldReference).definingClass }
            ?: throw PatchException("GrowthKit start-up: its logger is no longer read before the failure line")
        val starts = classDefBy(starter).methods.filter {
            it.returnType == LISTENABLE_FUTURE && it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val start = starts.singleOrNull() ?: throw PatchException("GrowthKit start-up: expected one start method in $starter, found ${starts.size}")
        mutableClassDefBy(starter).methods.single { it.name == start.name && it.returnType == LISTENABLE_FUTURE && it.parameterTypes.isEmpty() }.apply {
            if (implementation!!.registerCount < 2) throw PatchException("GrowthKit start has no free register")
            addInstructions(
                0,
                """
                    invoke-static {}, Lorg/ungoogled/gmail/Done;->nothing()$LISTENABLE_FUTURE
                    move-result-object v0
                    return-object v0
                """,
            )
        }
    }
}
