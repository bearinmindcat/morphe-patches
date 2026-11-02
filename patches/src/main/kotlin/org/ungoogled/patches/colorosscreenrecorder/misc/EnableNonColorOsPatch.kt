package org.ungoogled.patches.colorosscreenrecorder.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.colorosscreenrecorder.ColorOsScreenRecorderConstants.COMPATIBILITY_COLOROS_SCREEN_RECORDER
import org.ungoogled.patches.samsung.redirectCalls
import org.w3c.dom.Element

internal const val COLOROS_SCREEN_RECORDER_EXTENSION = "extensions/colorosscreenrecorder.mpe"

private const val RUNTIME = "Lorg/ungoogled/colorosscreenrecorder/ColorOS;"
private const val EXT = "Lorg/ungoogled/colorosscreenrecorder"
private const val PROJECTION = "$EXT/Projection;"
private const val WINDOWS = "$EXT/Windows;"
private const val SETTINGS_ACTIVITY = "com.oplus.screenrecorder.setting.RecordSettingsActivity"
private const val MAIN_ACTIVITY = "com.oplus.screenrecorder.MainActivity"
private const val START_ALIAS = "org.ungoogled.colorosscreenrecorder.StartRecording"
private const val CONSENT_ACTIVITY = "org.ungoogled.colorosscreenrecorder.ConsentActivity"
private const val RECORDER_SERVICE = "Lcom/oplus/screenrecorder/RecorderService;"

private val log = java.util.logging.Logger.getLogger("OnePlusScreenRecording")

/**
 * ColorOS opens the recorder's settings from Settings and starts recordings from Control Center; off ColorOS it has no
 * launcher icon, and its settings screen and recording starter are guarded by a ColorOS system permission
 * (OPLUS_COMPONENT_SAFE) no launcher holds. The settings screen gets a launcher icon and the starter a second one
 * ("Start recording", the app's own string; a recording still needs Android's capture consent each time), and Android's
 * screen capture dialog a screen of its own (see recordingConsentPatch).
 */
private val manifestPatch = resourcePatch(
    description = "Adds launcher icons for the recorder's settings and for starting a recording.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val activities = manifest.getElementsByTagName("activity")
            val settings = (0 until activities.length).map { activities.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == SETTINGS_ACTIVITY }
                ?: throw PatchException("$SETTINGS_ACTIVITY not in the manifest")
            settings.removeAttribute("android:permission")
            settings.appendChild(
                manifest.createElement("intent-filter").apply {
                    appendChild(manifest.createElement("action").apply { setAttribute("android:name", "android.intent.action.MAIN") })
                    appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.LAUNCHER") })
                },
            )
            val main = (0 until activities.length).map { activities.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == MAIN_ACTIVITY }
                ?: throw PatchException("$MAIN_ACTIVITY not in the manifest")
            main.removeAttribute("android:permission")
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("activity-alias").apply {
                    setAttribute("android:name", START_ALIAS)
                    setAttribute("android:targetActivity", MAIN_ACTIVITY)
                    setAttribute("android:exported", "true")
                    setAttribute("android:label", "@string/record_warning_dialog_sure")
                    appendChild(
                        manifest.createElement("intent-filter").apply {
                            appendChild(manifest.createElement("action").apply { setAttribute("android:name", "android.intent.action.MAIN") })
                            appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.LAUNCHER") })
                        },
                    )
                },
            )
            application.appendChild(
                manifest.createElement("activity").apply {
                    setAttribute("android:name", CONSENT_ACTIVITY)
                    setAttribute("android:exported", "false")
                    setAttribute("android:excludeFromRecents", "true")
                    setAttribute("android:taskAffinity", "")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                },
            )
        }
    }
}

private val SETTINGS_SHAPES = listOf(
    "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;" to "GetString",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I" to "GetInt",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I" to "GetInt",
    "getLong(Landroid/content/ContentResolver;Ljava/lang/String;J)J" to "GetLong",
    "getLong(Landroid/content/ContentResolver;Ljava/lang/String;)J" to "GetLong",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)F" to "GetFloat",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;)F" to "GetFloat",
    "putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z" to "PutString",
    "putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z" to "PutInt",
    "putLong(Landroid/content/ContentResolver;Ljava/lang/String;J)Z" to "PutLong",
    "putFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)Z" to "PutFloat",
)

/**
 * The recorder keeps its options and state in Settings.System, which an ordinary app may not write (see LocalSettings):
 * every settings call goes through LocalSettings.
 */
private val settingsPatch = bytecodePatch(
    description = "Keeps the settings Screen recording may not write in its own storage.",
) {
    extendWith(COLOROS_SCREEN_RECORDER_EXTENSION)

    execute {
        val calls = buildMap {
            for (table in listOf("Global", "Secure", "System")) {
                for ((shape, suffix) in SETTINGS_SHAPES) {
                    put("Landroid/provider/Settings\$$table;->$shape", "$EXT/LocalSettings;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}")
                }
            }
            put("Landroid/provider/Settings\$System;->canWrite(Landroid/content/Context;)Z", "$EXT/LocalSettings;->systemCanWrite(Landroid/content/Context;)Z")
        }
        val moved = redirectCalls(calls)
        log.info("Settings calls: $moved")
        // Measured against 16.5.12: 101 calls (98 reads/writes, 3 canWrite).
        if (moved < 60) throw PatchException("only $moved Settings calls found")
    }
}

/** q4.d.isAppEnabled(Context, String): getApplicationInfo(pkg).enabled. */
private object AppEnabledFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    strings = listOf("isAppEnabled with error : "),
)

/** Tingle's getService(Context, String): a system service by way of ColorOS's AppPlatform. */
private object TingleServiceFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    strings = listOf("Error : Slave cannot connect master."),
)

private fun Method.hasString(value: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
} == true

/**
 * The floating windows the recorder adds through ColorOS's AppPlatform become the app's own overlay windows where
 * AppPlatform is missing (see Windows), and its AppPlatform check passes there.
 */
private val windowsPatch = bytecodePatch(
    description = "Shows the recorder's floating windows without ColorOS's AppPlatform.",
) {
    extendWith(COLOROS_SCREEN_RECORDER_EXTENSION)

    execute {
        AppEnabledFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0, p1}, $WINDOWS->appEnabled(Landroid/content/Context;Ljava/lang/String;)I
                move-result v0
                if-ltz v0, :check
                return v0
            """,
            ExternalLabel("check", AppEnabledFingerprint.method.getInstruction(0)),
        )

        TingleServiceFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p0, p1}, $WINDOWS->tingleService(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :tingle
                return-object v0
            """,
            ExternalLabel("tingle", TingleServiceFingerprint.method.getInstruction(0)),
        )
        // Its release(Context, String) in the same class: hooks Android's window session back, nothing to undo here.
        val tingle = mutableClassDefBy(TingleServiceFingerprint.classDef.type)
        val releases = tingle.methods.filter {
            it.returnType == "V" && it.parameterTypes == listOf("Landroid/content/Context;", "Ljava/lang/String;") &&
                it.implementation != null
        }
        if (releases.size != 1) throw PatchException("Tingle release found ${releases.size} times")
        releases.single().apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {p0, p1}, $WINDOWS->skipTingleRelease(Landroid/content/Context;Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :release
                    return-void
                """,
                ExternalLabel("release", getInstruction(0)),
            )
        }

        val writes = rewritePackageNameWrites()
        log.info("Window packageName writes: $writes")
        // Measured against 16.5.12: 7 writes (AppPlatform's name for its windows, dialogs copying it).
        if (writes < 5) throw PatchException("only $writes window packageName writes found")
    }
}

/** Every `iput-object` to WindowManager.LayoutParams.packageName in the app -> Windows.setPackageName. */
private fun BytecodePatchContext.rewritePackageNameWrites(): Int {
    fun Any.isWrite(): Boolean {
        if (this !is ReferenceInstruction || (this as Instruction).opcode != Opcode.IPUT_OBJECT) return false
        val f = reference as? FieldReference ?: return false
        return f.definingClass == "Landroid/view/WindowManager\$LayoutParams;" && f.name == "packageName"
    }
    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) if (m.implementation?.instructions?.any { it.isWrite() } == true) sites += c.type to m
    }
    var moved = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            if (!insn.isWrite()) continue
            insn as TwoRegisterInstruction
            mutable.replaceInstruction(
                index,
                "invoke-static { v${insn.registerB}, v${insn.registerA} }, $WINDOWS->setPackageName(Landroid/view/WindowManager\$LayoutParams;Ljava/lang/String;)V",
            )
            moved++
        }
    }
    return moved
}

/** The recording starter: MainActivity with "recorder_entrance" and "recorder_auto_start" (the tile's way in). */
private object RecordStarterFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Landroid/os/Bundle;"),
    strings = listOf("recorder_auto_start", "com.oplus.screenrecorder.MainActivity"),
)

/** RecordPlatformUtil.isInternalAudioSupported: ColorOS's feature "oplus.software.audio.internal_record_support". */
private object InternalAudioFeatureFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("oplus.software.audio.internal_record_support"),
)

/** CodecUtil.isH265EncoderSupport: any HEVC encoder at all. */
private object HevcSupportFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("isH265EncoderSupport :unsupported"),
)

/** MainActivity.startRecordService(Context, Intent): starts RecorderService with the request's extras. */
private object StartRecordServiceFingerprint : Fingerprint(
    definingClass = "Lcom/oplus/screenrecorder/MainActivity;",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
    strings = listOf("startRecordService"),
)

/**
 * On ColorOS the recorder is a system app: it makes its own capture token through AppPlatform (Epona
 * IMediaProjection.createProjection) and goes foreground for screen capture without asking. Anywhere else both are
 * refused. Now MainActivity (where the Quick Settings tile, the recording notification's "record again" and other
 * entrances start a recording) opens Android's own capture dialog first, and the dialog's Start starts the recording
 * service with that consent; the recorder's two token makers hand out that consent (extension Projection).
 */
private val recordingConsentPatch = bytecodePatch(
    description = "Asks for Android's screen capture consent before each recording.",
) {
    dependsOn(manifestPatch)
    extendWith(COLOROS_SCREEN_RECORDER_EXTENSION)

    execute {
        StartRecordServiceFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static {p1, p2}, $PROJECTION->ask(Landroid/content/Context;Landroid/content/Intent;)Z
                move-result v0
                if-eqz v0, :start
                return-void
            """,
            ExternalLabel("start", StartRecordServiceFingerprint.method.getInstruction(0)),
        )

        // The token makers: static Intent x(String packageName, int uid) asking AppPlatform for createProjection.
        val makers = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.returnType == "Landroid/content/Intent;" && m.parameterTypes == listOf("Ljava/lang/String;", "I") &&
                    m.hasString("createProjection") && m.hasString("android.media.projection.IMediaProjection")
                ) makers += c.type to m
            }
        }
        // Measured against 16.5.12: two (the Android 11+ and the Android 14+ compat layers).
        if (makers.size < 2) throw PatchException("only ${makers.size} capture token makers found")
        for ((type, method) in makers) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            mutable.addInstructions(
                0,
                """
                    invoke-static {}, $PROJECTION->token()Landroid/content/Intent;
                    move-result-object v0
                    return-object v0
                """,
            )
        }

        // The tile opens MainActivity and closes the shade in one go (see Panels).
        RecordStarterFingerprint.method.apply {
            val index = implementation!!.instructions.indexOfFirst {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref?.name == "startActivity" && ref.parameterTypes == listOf("Landroid/content/Intent;")
            }
            if (index < 0) throw PatchException("the recording starter's startActivity not found")
            val call = getInstruction(index) as FiveRegisterInstruction
            replaceInstruction(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD} }, $EXT/Panels;->startActivity(Landroid/content/Context;Landroid/content/Intent;)V",
            )
        }

        // Device audio: the recorder captures it with Android's playback capture (AudioPlaybackCaptureConfiguration,
        // any Android 10+ phone) but offers it only where ColorOS's feature list says so; off ColorOS that list says
        // nothing, so the sound setting was "none" and every recording was silent.
        InternalAudioFeatureFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )

        // H.265 only with a hardware HEVC encoder (see Codecs).
        HevcSupportFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $EXT/Codecs;->hevc()Z
                move-result v0
                return v0
            """,
        )

        // A consent is good for one recording.
        val service = mutableClassDefBy(RECORDER_SERVICE)
        service.methods.firstOrNull { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
            ?.addInstructions(0, "invoke-static {}, $PROJECTION->clear()V")
            ?: throw PatchException("RecorderService.onDestroy not found")
    }
}

/**
 * Off ColorOS Screen recording has no launcher icon and dies on ColorOS framework classes and its Settings.System
 * writes. The extension carries stand-ins for the ColorOS classes it uses (generated by .github/scripts/coloros-standins);
 * on an OPPO / OnePlus / realme phone the framework's own classes load first, so they never run there. Its broadcast
 * receivers registered at run time are given the export flag Android 14+ demands of an installed app (see
 * exportDynamicReceivers). What ColorOS's AppPlatform did for it as a system app is done the ordinary way: Android's
 * capture consent before each recording, the floating control as an overlay window, its options in its own storage;
 * device audio (Android's playback capture) is offered, and H.265 only with a hardware encoder.
 */
@Suppress("unused")
val enableNonColorOsPatch = bytecodePatch(
    name = "Enable non-ColorOS ROM support",
    description = "Makes OnePlus Screen Recording installable and usable on non-ColorOS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_SCREEN_RECORDER)
    dependsOn(manifestPatch, settingsPatch, windowsPatch, recordingConsentPatch)
    extendWith(COLOROS_SCREEN_RECORDER_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers(RUNTIME)
        log.info("registerReceiver calls through ColorOS: $receivers")
        // Measured against 16.5.12: 18 calls.
        if (receivers < 12) throw PatchException("only $receivers registerReceiver calls found")
    }
}
