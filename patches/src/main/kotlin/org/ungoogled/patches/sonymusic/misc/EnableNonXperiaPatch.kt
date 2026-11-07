package org.ungoogled.patches.sonymusic.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.shared.supportedVersionCheck
import org.ungoogled.patches.sonymusic.SonyMusicConstants.COMPATIBILITY_SONY_MUSIC
import org.w3c.dom.Element

private val log = java.util.logging.Logger.getLogger("SonyMusic")

private const val AVAILABILITY = "Lcom/sonyericsson/music/common/AvailabilityUtils;"
private const val FIREBASE_APP = "Lcom/google/firebase/FirebaseApp;"

/** Where Music starts Firebase, each behind AvailabilityUtils.isAnalyticsDisable (measured against 9.4.16.A.1.17). */
private val FIREBASE_STARTS = setOf(
    "Lcom/sonyericsson/music/MusicApplication;",
    "Lcom/sonyericsson/music/datacollection/firebase/FirebaseCrashlyticsUtils;",
    "Lcom/sonyericsson/music/datacollection/firebase/FirebaseRemoteConfigProvider\$1;",
)

/**
 * Music requires Xperia's com.sony.device library (it names nothing from it), so Android refuses to install it
 * elsewhere. Every other Sony library is already optional and checked before use: Sony's audio effects (ClearAudio+,
 * DSEE HX) only below Android 9, the illumination LED, DLNA and Play Anywhere when their services or settings exist.
 * No stand-ins on purpose: those checks then answer "absent" and the features stay hidden.
 */
private val librariesPatch = resourcePatch(
    description = "Makes Music's Xperia libraries optional.",
) {
    execute {
        val required = makeLibrariesOptional()
        log.info("required libraries made optional: $required")
        // Measured against 9.4.16.A.1.17: one, com.sony.device.
        if (required == 0) throw PatchException("no required library found")

        // Firebase Analytics stays off even if something turned collection on.
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val metaData = (0 until application.childNodes.length).mapNotNull { application.childNodes.item(it) as? Element }
                .filter { it.tagName == "meta-data" }.associateBy { it.getAttribute("android:name") }
            if ("firebase_analytics_collection_enabled" !in metaData) {
                throw PatchException("no firebase_analytics_collection_enabled: the manifest changed shape")
            }
            val deactivated = metaData["firebase_analytics_collection_deactivated"] ?: manifest.createElement("meta-data").also {
                it.setAttribute("android:name", "firebase_analytics_collection_deactivated")
                application.appendChild(it)
            }
            deactivated.setAttribute("android:value", "true")
        }
    }
}

/**
 * Outside China Music starts Firebase as it starts, before any consent: Remote Config (Sony's feature switches and
 * "update available" version) through Firebase Installations, Firebase Sessions, and Crashlytics where Sony's own
 * usage-data consent allows it. Sony's builds that ship without Firebase answer true from isAnalyticsDisable; this one
 * now does too, so Firebase, Remote Config, Crashlytics and Google Analytics are never started. Defaults stand in for
 * the remote switches (Google Drive on, mora store off).
 */
private val telemetryPatch = bytecodePatch(
    description = "Keeps Firebase, Crashlytics and Google Analytics from starting.",
) {
    execute {
        val method = mutableClassDefByOrNull(AVAILABILITY)?.methods?.firstOrNull {
            it.name == "isAnalyticsDisable" && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
        } ?: throw PatchException("AvailabilityUtils.isAnalyticsDisable not found")
        method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )

        // Every place that starts Firebase checks that switch first; anything new would start it unchecked.
        val starts = mutableSetOf<String>()
        val unguarded = mutableListOf<String>()
        classDefForEach { c ->
            if (!c.type.startsWith("Lcom/sonyericsson/") && !c.type.startsWith("Lcom/sonymobile/")) return@classDefForEach
            for (m in c.methods) {
                val instructions = m.implementation?.instructions ?: continue
                val calls = instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                if (calls.none { it.definingClass == FIREBASE_APP && it.name == "initializeApp" }) continue
                starts += c.type
                if (c.type !in FIREBASE_STARTS) unguarded += "${c.type}->${m.name}"
            }
        }
        if (unguarded.isNotEmpty()) throw PatchException("Firebase started in unexpected places: $unguarded")
        if (starts != FIREBASE_STARTS) throw PatchException("Firebase start-up changed: found in $starts")
    }
}

@Suppress("unused")
val enableNonXperiaPatch = bytecodePatch(
    name = "Enable non-Xperia ROM support",
    description = "Makes Sony Music usable on non-Xperia ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SONY_MUSIC)
    dependsOn(supportedVersionCheck(COMPATIBILITY_SONY_MUSIC), librariesPatch, telemetryPatch)
}
