package org.ungoogled.patches.xiaomigalleryeditor.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val ONE_TRACK = "Lcom/xiaomi/onetrack/OneTrack;"

/** Firebase's start-up provider: initialises FirebaseApp in every process as the app starts. */
private object FirebaseInitProviderFingerprint : Fingerprint(
    definingClass = "Lcom/google/firebase/provider/FirebaseInitProvider;",
    name = "onCreate",
    returnType = "Z",
    parameters = listOf(),
)

/** The editor's Firebase helper set-up (FirebaseApp, Analytics, Crashlytics, the upload_log_pref observer). */
private object FirebaseHelperInitFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("mUploadLogPref ", "upload_log_pref"),
)

/** "GlobalMediaEditorPushAgent": asks Firebase Messaging for a push token when the first screen shows. */
private object PushAgentFingerprint : Fingerprint(
    name = "doFrame",
    returnType = "V",
    strings = listOf("GlobalMediaEditorPushAgent", "FirebaseApp not initialized, skip register push"),
)

/** Firebase's own switches: no automatic push token, Analytics off for good. */
private val firebaseSwitchesPatch = resourcePatch(
    description = "Switches off Firebase's automatic push registration and Analytics.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val children = application.childNodes.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
            if (children.none { it.tagName == "provider" && it.getAttribute("android:name") == "com.google.firebase.provider.FirebaseInitProvider" }) {
                throw PatchException("no FirebaseInitProvider in the manifest: the app changed shape")
            }
            val metaData = children.filter { it.tagName == "meta-data" }.associateBy { it.getAttribute("android:name") }
            for ((name, value) in mapOf(
                "firebase_messaging_auto_init_enabled" to "false",
                "firebase_analytics_collection_deactivated" to "true",
            )) {
                val element = metaData[name] ?: manifest.createElement("meta-data").also {
                    it.setAttribute("android:name", name)
                    application.appendChild(it)
                }
                element.setAttribute("android:value", value)
            }
        }
    }
}

/**
 * The Global editor started Firebase in every process at launch (its start-up provider and its own Firebase helper),
 * so Firebase Messaging -- which starts itself -- registered a push token with Google (a Firebase installation and a
 * Play services registration), and the editor's push agent asked for the token again on the first screen; nobody had
 * agreed to anything. Firebase is no longer started and the push agent does nothing. Xiaomi's usage statistics
 * (OneTrack) go only through HyperOS's own analytics service, as in Xiaomi's Recorder: off HyperOS, nowhere.
 */
internal val offlinePatch = bytecodePatch(
    description = "No Firebase or push registration; Xiaomi's statistics only through HyperOS.",
) {
    dependsOn(firebaseSwitchesPatch)

    execute {
        FirebaseInitProviderFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        FirebaseHelperInitFingerprint.method.addInstruction(0, "return-void")
        PushAgentFingerprint.method.addInstruction(0, "return-void")

        fun creates(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Boolean {
            val r = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            return instruction.opcode == Opcode.INVOKE_STATIC && r?.definingClass == ONE_TRACK && r.name == "createInstance"
        }
        val found = mutableListOf<Pair<String, String>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lcom/xiaomi/onetrack/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any(::creates) == true) {
                    found += c.type to "${m.name}${m.parameterTypes.joinToString("", "(", ")")}${m.returnType}"
                }
            }
        }
        val sites = found.map { (type, signature) ->
            val method = mutableClassDefBy(type).methods.first {
                "${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" == signature
            }
            val instructions = method.implementation!!.instructions.toList()
            val create = instructions.indexOfFirst(::creates)
            val after = if (instructions.getOrNull(create + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT) create + 2 else create + 1
            method.addInstruction(after, "invoke-static { }, $ONE_TRACK->setUseSystemNetTrafficOnly()V")
            "$type->$signature"
        }
        // Measured against 2.10.40.4.8-global: one, the Global track helper's set-up.
        if (sites.isEmpty()) throw PatchException("OneTrack set-up not found")
        java.util.logging.Logger.getLogger("XiaomiGalleryEditor").info("OneTrack set-ups: $sites")
    }
}
