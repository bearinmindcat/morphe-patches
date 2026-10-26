package org.ungoogled.patches.samsungkeyboard.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.w3c.dom.Element

private const val GSIM_LOGGER = "Lcom/samsung/android/sdk/handwriting/GSIMLogger;"
private const val SCPM_RECEIVER = "Lcom/samsung/android/honeyboard/base/broadcastreceiver/ScpmReceiver;"
private const val DIAGMON_AGENT = "com.sec.android.diagmonagent"

/** IME_FLAG_NO_PERSONALIZED_LEARNING: the field asks not to be learned from. */
private const val NO_PERSONALIZED_LEARNING = 0x1000000L

private fun MutableMethod.returnFirst(smali: String) = addInstructions(0, smali)

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Turns off Samsung Analytics, crash reports and typed-text collection.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        // Samsung Analytics: the tracker is never ready, so it neither starts nor sends.
        AnalyticsTrackerReadyFingerprint.method.returnFirst("const/4 v0, 0x0\nreturn v0")

        // DiagMon (crash, ANR and usage reports): the diagnostics agent reads as not installed, the SDK's own
        // "not supported" case, so no crash handler is set and no report is made. The start-up has the lookup
        // inlined; it asks for a package name nothing has.
        DiagMonAgentVersionFingerprint.method.returnFirst("const/4 v0, 0x0\nreturn v0")
        TelemetryStartFingerprint.method.let { start ->
            val instructions = start.implementation!!.instructions
            val lookups = instructions.withIndex().filter { (_, insn) ->
                insn.opcode == Opcode.CONST_STRING &&
                    ((insn as ReferenceInstruction).reference as StringReference).string == DIAGMON_AGENT
            }
            if (lookups.size != 1) throw PatchException("expected one diagnostics agent lookup at start-up, found ${lookups.size}")
            val (index, insn) = lookups.single()
            start.replaceInstruction(index, "const-string v${(insn as OneRegisterInstruction).registerA}, \"\"")
        }

        // Handwriting's usage survey to the diagnostics agent.
        mutableClassDefBy(GSIM_LOGGER).methods.single { it.name == "insertLogData" }.returnFirst("return-void")

        // Galaxy AI's consent upload.
        ConsentSyncFingerprint.method.returnFirst("const/4 v0, 0x0\nreturn v0")

        // Search-box text to Samsung's on-device data collector.
        TypedTextCollectionFingerprint.method.returnFirst("return-void")
    }
}

@Suppress("unused")
val noGifTrackingPatch = bytecodePatch(
    name = "No GIF tracking",
    description = "Stops GIPHY and Tenor view and share pings.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        // Samsung's GIF revenue share: GIPHY sessions stay queued (the queue keeps its last 10), Tenor's
        // register URL is never opened -- what each already does with nothing to send.
        GiphyPingbackFingerprint.method.returnFirst("return-void")
        TenorRegisterFingerprint.method.returnFirst("const/4 v0, 0x0\nreturn-object v0")
    }
}

@Suppress("unused")
val stopRemoteUpdatesPatch = bytecodePatch(
    name = "Stop remote updates",
    description = "Ignores keyboard policies pushed from Samsung's servers.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        // Samsung's policy service (SCPM) delivers the keyboard's server-side lists and switches -- word
        // blocklists, writing-assist and sticker rules -- through this one receiver.
        mutableClassDefBy(SCPM_RECEIVER).methods.single { it.name == "onReceive" }.returnFirst("return-void")
    }
}

@Suppress("unused")
val alwaysIncognitoPatch = bytecodePatch(
    name = "Always incognito",
    description = "Never learns from what you type.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        // The keyboard's incognito check -- true in a field that asks not to be learned from, a private field or
        // the Secure Folder -- answers yes everywhere. It is the no-argument boolean that tests that flag and
        // that the "is learning off?" checks (the ones reading a field's disableLearning option) call.
        fun Method.strings() = implementation?.instructions?.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.orEmpty()

        val flagTests = mutableSetOf<Triple<String, String, String>>()
        val learningChecks = mutableSetOf<Triple<String, String, String>>()
        classDefForEach { c ->
            for (m in c.methods) {
                if (m.returnType == "Z" && m.parameterTypes.isEmpty() && m.implementation?.instructions?.any {
                        (it as? WideLiteralInstruction)?.wideLiteral == NO_PERSONALIZED_LEARNING
                    } == true
                ) flagTests += Triple(c.type, m.name, "()Z")
                if ("disableLearning" in m.strings()) {
                    m.implementation!!.instructions.forEach { insn ->
                        val r = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEach
                        if (r.returnType == "Z" && r.parameterTypes.isEmpty()) learningChecks += Triple(r.definingClass, r.name, "()Z")
                    }
                }
            }
        }
        val incognito = (flagTests intersect learningChecks).singleOrNull()
            ?: throw PatchException("incognito check not found: ${flagTests intersect learningChecks}")
        mutableClassDefBy(incognito.first).methods
            .single { it.name == incognito.second && it.returnType == "Z" && it.parameterTypes.isEmpty() }
            .returnFirst("const/4 v0, 0x1\nreturn v0")
    }
}

@Suppress("unused")
val removeInternetPermissionPatch = resourcePatch(
    name = "Remove internet permission",
    description = "Keeps the keyboard offline. GIFs, stickers, translation, AI tools and language downloads stop working.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        document("AndroidManifest.xml").use { manifest ->
            val list = manifest.getElementsByTagName("uses-permission")
            val internet = (0 until list.length).map { list.item(it) as Element }
                .filter { it.getAttribute("android:name") == "android.permission.INTERNET" }
            if (internet.isEmpty()) throw PatchException("no INTERNET permission in the manifest")
            internet.forEach { it.parentNode.removeChild(it) }
        }
    }
}
