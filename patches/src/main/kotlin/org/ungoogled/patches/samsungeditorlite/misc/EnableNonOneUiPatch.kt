package org.ungoogled.patches.samsungeditorlite.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsungeditorlite.SamsungEditorLiteConstants.COMPATIBILITY_SAMSUNG_EDITOR_LITE
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element

internal const val SAMSUNG_EDITOR_LITE_EXTENSION = "extensions/samsungeditorlite.mpe"
private const val SERVICES = "Lorg/ungoogled/samsungeditorlite/Services;"
private const val INTENTS = "Lorg/ungoogled/samsungeditorlite/Intents;"
private const val ENTRY_ACTIVITY = "com.samsung.app.newtrim.SMVideoTrimmerActivity"

/**
 * Editor Lite has no launcher icon and only Samsung Gallery opens it (by name); elsewhere it is also offered as an
 * editor for any video. Every Samsung library it lists is already optional (checked).
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            for (tag in listOf("uses-library", "uses-native-library")) {
                val list = manifest.getElementsByTagName(tag)
                for (i in 0 until list.length) (list.item(i) as Element).setAttribute("android:required", "false")
            }
            val activities = manifest.getElementsByTagName("activity")
            val entry = (0 until activities.length).map { activities.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == ENTRY_ACTIVITY }
                ?: throw PatchException("$ENTRY_ACTIVITY not found in the manifest")
            entry.appendChild(
                manifest.createElement("intent-filter").apply {
                    appendChild(manifest.createElement("action").apply { setAttribute("android:name", "android.intent.action.EDIT") })
                    appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.DEFAULT") })
                    appendChild(manifest.createElement("data").apply { setAttribute("android:mimeType", "video/*") })
                },
            )
        }
    }
}

/** Sends Editor Lite's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_EDITOR_LITE_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers(
            "Lorg/ungoogled/samsungeditorlite/OneUi;", "Lorg/ungoogled/samsungeditorlite/Sem;",
        )
        java.util.logging.Logger.getLogger("SamsungEditorLite").info("One UI members: $calls calls, $fields fields")
        // Measured against 4.0.85.20: about 60 calls (button shapes, hover popups, pop-over options ...).
        if (calls < 30) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * The preview pauses for phone calls by reading the call state, which needs READ_PHONE_STATE (Samsung's own build is
 * granted it): elsewhere it threw and the editor closed. The extension answers it from the audio mode when refused.
 */
private val callStatePatch = bytecodePatch(
    description = "Answers the call state where the phone refuses it.",
) {
    extendWith(SAMSUNG_EDITOR_LITE_EXTENSION)

    execute {
        if (redirectSystemServices(SERVICES) == 0) throw PatchException("no getSystemService calls found")
        val moved = redirectCalls(
            mapOf(
                "Landroid/telephony/TelephonyManager;->registerTelephonyCallback(Ljava/util/concurrent/Executor;Landroid/telephony/TelephonyCallback;)V" to
                    "$SERVICES->registerTelephonyCallback(Landroid/telephony/TelephonyManager;Ljava/util/concurrent/Executor;Landroid/telephony/TelephonyCallback;)V",
                "Landroid/telephony/TelephonyManager;->unregisterTelephonyCallback(Landroid/telephony/TelephonyCallback;)V" to
                    "$SERVICES->unregisterTelephonyCallback(Landroid/telephony/TelephonyManager;Landroid/telephony/TelephonyCallback;)V",
                "Landroid/telephony/TelephonyManager;->getCallState()I" to "$SERVICES->getCallState(Landroid/telephony/TelephonyManager;)I",
            ),
        )
        // Measured against 4.0.85.20: 3 getCallState, 3 registerTelephonyCallback, some unregister.
        if (moved < 4) throw PatchException("only $moved call state calls found")
    }
}

/** A video opened with another app's "Edit" reaches the editor the way Gallery's does (see Intents). */
private val editIntentPatch = bytecodePatch(
    description = "Opens videos sent with Edit from any app.",
) {
    extendWith(SAMSUNG_EDITOR_LITE_EXTENSION)

    execute {
        val type = "L" + ENTRY_ACTIVITY.replace('.', '/') + ";"
        val onCreate = (mutableClassDefByOrNull(type) ?: throw PatchException("Editor Lite has no $type"))
            .methods.firstOrNull { it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;") }
            ?: throw PatchException("no onCreate(Bundle) in $type")
        onCreate.addInstructions(0, "invoke-static { p0 }, $INTENTS->adopt(Landroid/app/Activity;)V")
    }
}

/**
 * Saving a cut from Gallery replaces the video and keeps the original for "Revert" in Samsung's own storage
 * (/data/sec, moved there by a One UI storage call) registered with One UI's media provider: elsewhere the move
 * failed, and the editor crashed on the missing provider after writing the cut. The cut is saved as a new video next
 * to the original instead, which stays as it was.
 */
private val saveAsCopyPatch = bytecodePatch(
    description = "Saves cuts as a new video next to the original.",
) {
    execute {
        NonDestructiveFlagFingerprint.method.addInstructions(0, "const/4 p0, 0x0")
    }
}

private const val UPDATE_CHECK = "https://vas.samsungapps.com/stub/stubUpdateCheck.as"

/**
 * "About Editor Lite" (and the editor once a month) asked Samsung's update server for a newer version, sending the
 * phone's model, carrier and Android ID; the newer version would be Samsung's, which can't replace this copy. The
 * check now answers "up to date" without asking.
 */
private val updateCheckPatch = bytecodePatch(
    description = "Turns off the update check.",
) {
    execute {
        val checks = mutableListOf<app.morphe.patcher.util.proxy.mutableTypes.MutableMethod>()
        classDefForEach { c ->
            c.methods.filter { m ->
                m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == UPDATE_CHECK
                } == true
            }.forEach { m ->
                checks += mutableClassDefBy(c.type).methods.single {
                    it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == m.returnType
                }
            }
        }
        // Measured against 4.0.85.20: the About page's check (with a result callback) and the monthly one.
        if (checks.size < 2) throw PatchException("only ${checks.size} update checks found")
        // The About page's callback: its "no update needed" answer is the method that logs onUpdateNotNecessary.
        var answer: String? = null
        classDefForEach { c ->
            c.methods.firstOrNull { m ->
                m.parameterTypes.isEmpty() && m.returnType == "V" && m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "onUpdateNotNecessary"
                } == true
            }?.let { answer = it.name }
        }
        val upToDate = answer ?: throw PatchException("no onUpdateNotNecessary callback found")
        for (check in checks) {
            val callback = check.parameterTypes.singleOrNull()?.toString()
            if (check.returnType == "V" && callback != null && callback != "Landroid/content/Context;") {
                check.addInstructions(0, "invoke-interface { p0 }, $callback->$upToDate()V\nreturn-void")
            } else {
                check.addInstructions(0, "return-void")
            }
        }
    }
}

/**
 * Off One UI Editor Lite dies at its first screen on One UI classes it calls (SemSystemProperties first), previews
 * with One UI's SemMediaPlayer, draws its timeline with SemAsyncVideoFrameDecoder and saves every cut with One UI's
 * transcoder. The extension carries stand-ins for the One UI classes it names, and those media classes rebuilt on
 * Android's own MediaPlayer, MediaMetadataRetriever, MediaExtractor, MediaCodec and MediaMuxer.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Editor Lite usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_EDITOR_LITE)
    dependsOn(manifestPatch, oneUiMembersPatch, callStatePatch, editIntentPatch, saveAsCopyPatch, updateCheckPatch)
    extendWith(SAMSUNG_EDITOR_LITE_EXTENSION)
}
