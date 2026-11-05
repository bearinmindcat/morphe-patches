package org.ungoogled.patches.samsungkeyboard.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
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
import org.ungoogled.patches.samsung.hideDiagnosticsAgent
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.w3c.dom.Element

private const val GSIM_LOGGER = "Lcom/samsung/android/sdk/handwriting/GSIMLogger;"
private const val SCPM_RECEIVER = "Lcom/samsung/android/honeyboard/base/broadcastreceiver/ScpmReceiver;"

/** IME_FLAG_NO_PERSONALIZED_LEARNING: the field asks not to be learned from. */
private const val NO_PERSONALIZED_LEARNING = 0x1000000L

private fun MutableMethod.returnFirst(smali: String) = addInstructions(0, smali)

/** Returns at once with nothing: void, false / 0, or null. */
private fun MutableMethod.returnNothing() = returnFirst(
    when (returnType.first()) {
        'V' -> "return-void"
        'L', '[' -> "const/4 v0, 0x0\nreturn-object v0"
        'J', 'D' -> "const-wide/16 v0, 0x0\nreturn-wide v0"
        else -> "const/4 v0, 0x0\nreturn v0"
    },
)

private val log = java.util.logging.Logger.getLogger("SamsungKeyboard")

private fun Method.strings() = implementation?.instructions?.mapNotNull {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
}.orEmpty()

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

/** Every method [predicate] accepts (read-only pass). */
private fun BytecodePatchContext.methodsWhere(predicate: (Method) -> Boolean): List<Method> {
    val found = mutableListOf<Method>()
    classDefForEach { c -> c.methods.filterTo(found) { it.implementation != null && predicate(it) } }
    return found
}

/**
 * Patches [fingerprint]'s method, or -- when this build has no [marker] string anywhere, i.e. the feature isn't in it --
 * logs that and skips it. A build that has the feature in a shape the fingerprint misses still fails.
 */
private fun BytecodePatchContext.whereBuilt(what: String, marker: String, fingerprint: Fingerprint, patch: MutableMethod.() -> Unit) {
    val method = fingerprint.methodOrNull
    if (method != null) return method.patch()
    if (methodsWhere { marker in it.strings() }.isNotEmpty()) throw PatchException("$what: not found where this build has it ($marker)")
    log.info("$what: not in this build, nothing to turn off")
}

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
        // "not supported" case, so no crash handler is set and no report is made. Every lookup of the agent asks for
        // a package name nothing has, wherever R8 put it (issue #98: the start-up method and the SDKs' version checks
        // differ between builds of the same version); the version checks also answer 0 themselves.
        val agentLookups = hideDiagnosticsAgent()
        val versionChecks = methodsWhere {
            it.returnType == "I" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
                "DMA Client is not exist" in it.strings()
        }
        versionChecks.forEach { mutable(it).returnFirst("const/4 v0, 0x0\nreturn v0") }
        log.info("DiagMon: $agentLookups agent lookups, ${versionChecks.size} version checks")

        // Handwriting's usage survey to the diagnostics agent.
        mutableClassDefBy(GSIM_LOGGER).methods.single { it.name == "insertLogData" }.returnFirst("return-void")

        // Galaxy AI's consent upload (not in every build).
        whereBuilt("Galaxy AI consent upload", "canSyncNow=false isSamsungAccountLoggedIn=", ConsentSyncFingerprint) {
            returnFirst("const/4 v0, 0x0\nreturn v0")
        }

        // Search-box text to Samsung's on-device data collector.
        whereBuilt("Typed-text collection", "[[AiWriter]_DATA_COLLECTION]", TypedTextCollectionFingerprint) {
            returnFirst("return-void")
        }
    }
}

@Suppress("unused")
val noGifTrackingPatch = bytecodePatch(
    name = "No GIF tracking",
    description = "Stops GIPHY and Tenor view and share pings.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        // Samsung's GIF revenue share: GIPHY's sessions are never sent (the queue's sender returns, or each session's
        // request is never made -- its Future is unused), Tenor's register URL is never opened -- what each already
        // does with nothing to send.
        whereBuilt("GIPHY pingbacks", "pingback_id", GiphyPingbackFingerprint) { returnNothing() }
        whereBuilt("Tenor register", "GRS_TenorRegisterManager", TenorRegisterFingerprint) { returnNothing() }
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

private const val SECURE_FOLDER_ID = "Lcom/samsung/android/knox/SemPersonaManager;->isSecureFolderId(I)Z"
private const val INPUT_BINDING_UID = "Landroid/view/inputmethod/InputBinding;->getUid()I"

private fun MethodReference.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.calls() = implementation?.instructions?.mapNotNull {
    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.key()
}.orEmpty()

@Suppress("unused")
val alwaysIncognitoPatch = bytecodePatch(
    name = "Always incognito",
    description = "Never learns from what you type.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)

    execute {
        // The keyboard's incognito check -- true in a field that asks not to be learned from, a private field or
        // the Secure Folder -- answers yes everywhere. It is the no-argument boolean that asks the Secure Folder test
        // (the input binding's user is a Secure Folder) and tests that flag, itself or through the no-argument
        // booleans it calls (R8 inlines them in some builds, not in others).
        val booleans = mutableMapOf<String, Method>()
        val secureFolderTests = mutableSetOf<String>()
        classDefForEach { c ->
            for (m in c.methods) {
                if (m.implementation == null) continue
                if (m.returnType == "Z" && m.parameterTypes.isEmpty()) booleans[m.key()] = m
                val calls = m.calls()
                if (SECURE_FOLDER_ID in calls && INPUT_BINDING_UID in calls) secureFolderTests += m.key()
            }
        }
        fun testsFlag(m: Method, depth: Int): Boolean =
            m.implementation!!.instructions.any { (it as? WideLiteralInstruction)?.wideLiteral == NO_PERSONALIZED_LEARNING } ||
                depth > 0 && m.calls().any { key -> booleans[key]?.let { testsFlag(it, depth - 1) } == true }
        val incognito = booleans.values.filter { m -> m.calls().any { it in secureFolderTests } && testsFlag(m, 2) }
            .singleOrNull() ?: throw PatchException("incognito check not found (Secure Folder tests: $secureFolderTests)")
        mutable(incognito).returnFirst("const/4 v0, 0x1\nreturn v0")
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
