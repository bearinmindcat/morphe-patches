package org.ungoogled.patches.xiaomiscreenrecorder.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.xiaomi.redirectMiuiMembers
import org.ungoogled.patches.xiaomiscreenrecorder.XiaomiScreenRecorderConstants.COMPATIBILITY_XIAOMI_SCREEN_RECORDER
import org.w3c.dom.Element

internal const val XIAOMI_SCREEN_RECORDER_EXTENSION = "extensions/xiaomiscreenrecorder.mpe"

private const val PROJECTION = "Lorg/ungoogled/xiaomiscreenrecorder/Projection;"
private const val AUDIO = "Lorg/ungoogled/xiaomiscreenrecorder/Audio;"
private const val CONSENT_ACTIVITY = "org.ungoogled.xiaomiscreenrecorder.ConsentActivity"
private const val SETTINGS_ACTIVITY = "com.miui.screenrecorder.activity.ScreenRecorderSettingActivity2"
private const val RECORDER_SERVICE = "Lcom/miui/screenrecorder/service/RecorderService;"
private const val RECORDER_SERVICE_NAME = "com.miui.screenrecorder.service.RecorderService"

private val log = java.util.logging.Logger.getLogger("XiaomiScreenRecorder")

/**
 * The manifest requires micloud-sdk, a HyperOS library, and the system's shared user: without them Android refuses
 * the install. Off HyperOS the app has no launcher icon either (HyperOS opens its settings from Settings and Control
 * Center), so its settings screen gets one; the recording service declares the microphone (see
 * Projection.startForeground); and Android's screen capture dialog gets a screen of its own (see
 * recordingConsentPatch).
 */
private val manifestPatch = resourcePatch(
    description = "Lets Xiaomi Screen Recorder install without HyperOS's libraries and adds a launcher icon.",
) {
    execute {
        val required = makeLibrariesOptional()
        log.info("Required libraries made optional: $required")
        // Measured against 4.16.2.9.1: micloud-sdk.
        if (required < 1) throw PatchException("no required uses-library found")

        document("AndroidManifest.xml").use { manifest ->
            // android.uid.system up to Android 12 (sharedUserMaxSdkVersion 32): only the platform's key may join it,
            // so Android 10-12 would refuse the install; Android 13+ ignores it for new installs anyway.
            manifest.documentElement.removeAttribute("android:sharedUserId")
            manifest.documentElement.removeAttribute("android:sharedUserMaxSdkVersion")

            val activities = manifest.getElementsByTagName("activity")
            val settings = (0 until activities.length).map { activities.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == SETTINGS_ACTIVITY }
                ?: throw PatchException("$SETTINGS_ACTIVITY not in the manifest")
            // Guarded by READ_FRAME_BUFFER (a platform permission), which a launcher doesn't hold.
            settings.removeAttribute("android:permission")
            settings.removeAttribute("android:excludeFromRecents")
            settings.appendChild(
                manifest.createElement("intent-filter").apply {
                    appendChild(manifest.createElement("action").apply { setAttribute("android:name", "android.intent.action.MAIN") })
                    appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.LAUNCHER") })
                },
            )

            // The recording service may use the microphone from the background (see Projection.startForeground).
            val services = manifest.getElementsByTagName("service")
            val recorder = (0 until services.length).map { services.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == RECORDER_SERVICE_NAME }
                ?: throw PatchException("$RECORDER_SERVICE_NAME not in the manifest")
            recorder.setAttribute("android:foregroundServiceType", "mediaProjection|microphone")
            val application = manifest.getElementsByTagName("application").item(0) as Element
            manifest.documentElement.insertBefore(
                manifest.createElement("uses-permission").apply {
                    setAttribute("android:name", "android.permission.FOREGROUND_SERVICE_MICROPHONE")
                },
                application,
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

/** Sends the app's uses of HyperOS's additions to Android classes through Miui (see redirectMiuiMembers). */
private val miuiMembersPatch = bytecodePatch(
    description = "Answers HyperOS's additions to Android classes where the phone has none.",
) {
    extendWith(XIAOMI_SCREEN_RECORDER_EXTENSION)

    execute {
        val (calls, fields) = redirectMiuiMembers(
            "Lorg/ungoogled/xiaomiscreenrecorder/Miui;", "Lorg/ungoogled/xiaomiscreenrecorder/MiuiShims;",
        )
        log.info("HyperOS members: $calls calls, $fields fields")
        // Measured against 4.16.2.9.1: 5 calls (miuix's ActivityOptions transitions).
        if (calls < 5) throw PatchException("only $calls HyperOS calls found")
    }
}

/** The recording controller's start: a MediaProjection from HyperOS's privileged capture token. */
private object StartRecordingFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("start record fail, reason : projectionManager is null", "MediaProjection is null."),
)

/**
 * On HyperOS the recorder is a system app: it makes its own capture token (IMediaProjectionManager.createProjection,
 * MANAGE_MEDIA_PROJECTION) and goes foreground for screen capture without asking. Anywhere else both are refused
 * ("Starting FGS with type mediaProjection ... requires permissions"), so the record button did nothing. Now the
 * recording service, right before it goes foreground, opens Android's own capture dialog when it has no consent for
 * this recording, and the dialog's Start starts the recording with that consent (extension Projection). The methods
 * other bundles patch for this app (the token maker, the app's permission screen, the tile's click) stay untouched.
 */
private val recordingConsentPatch = bytecodePatch(
    description = "Asks for Android's screen capture consent before each recording.",
) {
    dependsOn(manifestPatch)
    extendWith(XIAOMI_SCREEN_RECORDER_EXTENSION)

    execute {
        // 1. RecorderService: the one method calling startForeground, and the call to it in the start-recording path.
        val service = mutableClassDefBy(RECORDER_SERVICE)
        val goForeground = service.methods.singleOrNull { m ->
            m.parameterTypes.isEmpty() && m.returnType == "V" && m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "startForeground"
            } == true
        } ?: throw PatchException("RecorderService's startForeground method not found")
        // It goes foreground through the extension, which adds the microphone type where the microphone may be used.
        goForeground.implementation!!.instructions.toList().forEachIndexed { index, instruction ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
            if (ref.name != "startForeground" || ref.parameterTypes != listOf("I", "Landroid/app/Notification;")) return@forEachIndexed
            val registers = registersOf(instruction).joinToString(", ") { "v$it" }
            goForeground.replaceInstruction(
                index,
                "invoke-static {$registers}, $PROJECTION->startForeground(Landroid/app/Service;ILandroid/app/Notification;)V",
            )
        }
        var asks = 0
        service.methods.filter { it.parameterTypes == listOf("Z", "Z") && it.returnType == "V" }.forEach { start ->
            val instructions = start.implementation!!.instructions.toList()
            val call = instructions.indexOfFirst {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref != null && ref.definingClass == RECORDER_SERVICE && ref.name == goForeground.name &&
                    ref.parameterTypes.isEmpty()
            }
            if (call < 1) return@forEach
            // Entered from "if-eqz vN" on the controller's go-ahead; vN is written again right after the call.
            val branch = instructions[call - 1]
            if (branch.opcode != Opcode.IF_EQZ) throw PatchException("unexpected ${branch.opcode} before going foreground")
            val register = (branch as OneRegisterInstruction).registerA
            val next = instructions[call + 1]
            if (next.opcode != Opcode.NEW_INSTANCE || (next as OneRegisterInstruction).registerA != register) {
                throw PatchException("v$register is still live after going foreground")
            }
            start.addInstructionsWithLabels(
                call,
                """
                    invoke-static {p0}, $PROJECTION->ask(Landroid/app/Service;)Z
                    move-result v$register
                    if-eqz v$register, :record
                    return-void
                """,
                ExternalLabel("record", start.getInstruction(call)),
            )
            asks++
        }
        if (asks != 1) throw PatchException("start-recording path found $asks times")

        // 2. The controller takes its MediaProjection from the consent, where there is one, and never asks HyperOS for
        // a token then (off HyperOS that call throws).
        StartRecordingFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val token = instructions.indexOfFirst {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_STATIC && ref != null && ref.returnType == "Landroid/content/Intent;" &&
                    ref.parameterTypes == listOf("Landroid/content/Context;")
            }
            if (token < 0 || instructions[token + 1].opcode != Opcode.MOVE_RESULT_OBJECT) {
                throw PatchException("HyperOS capture token call not found")
            }
            val result = (instructions[token + 1] as OneRegisterInstruction).registerA
            // Measured against 4.16.2.9.1: move-result-object v3; const/4 v4, -0x1;
            // invoke-virtual {.., v4, v3}, MediaProjectionManager->getMediaProjection(ILandroid/content/Intent;).
            val code = instructions[token + 2]
            val project = instructions[token + 3]
            val projectRef = (project as? ReferenceInstruction)?.reference as? MethodReference
            if (code.opcode != Opcode.CONST_4 || projectRef?.name != "getMediaProjection") {
                throw PatchException("getMediaProjection(-1, token) not right after the token call")
            }
            val scratch = (code as OneRegisterInstruction).registerA
            if (scratch == result) throw PatchException("no scratch register next to the token call")
            addInstructionsWithLabels(
                token,
                """
                    invoke-static {}, $PROJECTION->take()Landroid/content/Intent;
                    move-result-object v$scratch
                    if-eqz v$scratch, :privileged
                    move-object v$result, v$scratch
                    goto :consent
                """,
                ExternalLabel("privileged", getInstruction(token)),
                ExternalLabel("consent", getInstruction(token + 2)),
            )
        }
    }
}

/** VideoRecorder's codec choice: true when the phone has any encoder whose name contains "hevc". */
private object HevcEncoderFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("hevc"),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getCodecCount"
        } == true
    },
)

/**
 * The recorder encodes HEVC whenever the phone lists any HEVC encoder. Every Android 10+ phone lists Google's
 * software one, which takes at most 512x512, so where no hardware HEVC encoder exists (the emulator, some phones)
 * every recording failed at "unable to configure video encoder". HEVC now needs a hardware encoder; else H.264.
 */
private val videoCodecPatch = bytecodePatch(
    description = "Records HEVC only where a hardware encoder does it, H.264 otherwise.",
) {
    extendWith(XIAOMI_SCREEN_RECORDER_EXTENSION)

    execute {
        HevcEncoderFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lorg/ungoogled/xiaomiscreenrecorder/Codecs;->hevc()Z
                move-result v0
                return v0
            """,
        )
    }
}

/** The audio recorder's setup: picks the recorder class and whether call audio is captured too (by region). */
private object PrepareAudioFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("prepareAudioRecorder inCall: "),
)

private const val TELEPHONY_LISTEN = "Landroid/telephony/TelephonyManager;->listen(Landroid/telephony/PhoneStateListener;I)V"

/** Registers an instruction reads or writes, as far as this patch needs to know. */
private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
    is FiveRegisterInstruction -> listOf(
        instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
    ).take(instruction.registerCount)
    is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
    is ThreeRegisterInstruction -> listOf(instruction.registerA, instruction.registerB, instruction.registerC)
    is TwoRegisterInstruction -> listOf(instruction.registerA, instruction.registerB)
    is OneRegisterInstruction -> listOf(instruction.registerA)
    else -> emptyList()
}

/**
 * App audio (AudioPlaybackCapture through the user's capture consent) works for any app, but the recorder set it up
 * as HyperOS's system app may: it watched the call state with TelephonyManager.listen, which needs READ_PHONE_STATE
 * ("SecurityException: listen" -- every recording with sound failed before its first frame), and in some regions
 * (Russia, Turkey, Mexico, Hong Kong, ...) it also asks for call audio, which needs CAPTURE_VOICE_COMMUNICATION_OUTPUT.
 * Now the call-state watch is skipped where Android refuses it, and call audio is asked for only with that permission.
 */
private val appAudioPatch = bytecodePatch(
    description = "Records app audio without HyperOS's system permissions.",
) {
    extendWith(XIAOMI_SCREEN_RECORDER_EXTENSION)

    execute {
        var listens = 0
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            val calls = classDef.methods.any { m ->
                m.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == TELEPHONY_LISTEN } == true
            }
            if (!calls) return@classDefForEach
            mutableClassDefBy(classDef.type).methods.forEach { method ->
                method.implementation?.instructions?.toList()?.forEachIndexed { index, instruction ->
                    if ((instruction as? ReferenceInstruction)?.reference?.toString() != TELEPHONY_LISTEN) return@forEachIndexed
                    val registers = registersOf(instruction).joinToString(", ") { "v$it" }
                    val invoke = if (instruction is RegisterRangeInstruction) "invoke-static/range" else "invoke-static"
                    method.replaceInstruction(
                        index,
                        "$invoke {$registers}, $AUDIO->listen(Landroid/telephony/TelephonyManager;Landroid/telephony/PhoneStateListener;I)V",
                    )
                    listens++
                }
            }
        }
        log.info("Call-state watches: $listens")
        // Measured against 4.16.2.9.1: 2 in the app-audio recorder (listen and unlisten).
        if (listens < 1) throw PatchException("no TelephonyManager.listen call found")

        PrepareAudioFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val region = instructions.withIndex().filter { (_, it) ->
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_STATIC && ref != null && ref.returnType == "I" && ref.parameterTypes.isEmpty()
            }.singleOrNull()?.index ?: throw PatchException("call-audio region check not found")
            val result = instructions[region + 1] as? OneRegisterInstruction
            if (instructions[region + 1].opcode != Opcode.MOVE_RESULT || result == null) {
                throw PatchException("region check result not found")
            }
            // The Context the audio recorder was built with: its register is free from here on.
            val contextRead = instructions.subList(0, region).lastOrNull {
                it.opcode == Opcode.IGET_OBJECT &&
                    ((it as ReferenceInstruction).reference as FieldReference).type == "Landroid/content/Context;"
            } ?: throw PatchException("audio recorder context not found")
            val context = (contextRead as OneRegisterInstruction).registerA
            val field = (contextRead as ReferenceInstruction).reference as FieldReference
            if (instructions.drop(region + 2).any { context in registersOf(it) }) {
                throw PatchException("v$context is used after the region check")
            }
            addInstructions(
                region + 2,
                """
                    iget-object v$context, p0, $field
                    invoke-static {v$context, v${result.registerA}}, $AUDIO->voiceCalls(Landroid/content/Context;I)I
                    move-result v${result.registerA}
                """,
            )
        }
    }
}

/** The app's shade-collapse helper: StatusBarManager.collapsePanels() by reflection. */
private object CollapsePanelsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("statusbar", "collapsePanels"),
)

private const val QUICK_SERVICE = "Lcom/miui/screenrecorder/service/QuickService;"

/**
 * A tap on the Screen Recorder tile left the notification shade open over the recorder's floating control: the app
 * closes it with StatusBarManager's hidden collapsePanels(), which Android refuses to anything but a system app. The
 * tile now closes it the way any tile may (extension Panels). The tile's own click handler stays untouched.
 */
private val tileShadePatch = bytecodePatch(
    description = "Closes the notification shade after a tap on the Screen Recorder tile.",
) {
    dependsOn(manifestPatch)
    extendWith(XIAOMI_SCREEN_RECORDER_EXTENSION)

    execute {
        val listening = mutableClassDefBy(QUICK_SERVICE).methods.firstOrNull {
            it.name == "onStartListening" && it.parameterTypes.isEmpty() && it.returnType == "V"
        } ?: throw PatchException("QuickService.onStartListening not found")
        listening.addInstructions(
            0,
            "invoke-static {p0}, Lorg/ungoogled/xiaomiscreenrecorder/Panels;->listening(Landroid/service/quicksettings/TileService;)V",
        )

        CollapsePanelsFingerprint.method.apply {
            if (!AccessFlags.STATIC.isSet(accessFlags)) throw PatchException("collapse helper is not static")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, Lorg/ungoogled/xiaomiscreenrecorder/Panels;->collapse()Z
                    move-result v0
                    if-eqz v0, :stock
                    return-void
                """,
                ExternalLabel("stock", getInstruction(0)),
            )
        }
    }
}

/**
 * Off HyperOS Screen Recorder is refused at install (micloud-sdk), reads HyperOS classes (miui.os.Build,
 * miui.util.FeatureParser) as it starts, and as a regular app can't capture the screen, its microphone or app audio
 * the way HyperOS's preinstalled recorder does (see the patches above). The extension carries stand-ins for the 13
 * HyperOS classes it uses (generated by .github/scripts/xiaomi-standins); on a Xiaomi phone the framework's own
 * classes load first, so they never run there.
 */
@Suppress("unused")
val enableNonHyperOsPatch = bytecodePatch(
    name = "Enable non-HyperOS ROM support",
    description = "Makes Xiaomi Screen Recorder installable and usable on non-Xiaomi ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_XIAOMI_SCREEN_RECORDER)
    dependsOn(manifestPatch, miuiMembersPatch, recordingConsentPatch, videoCodecPatch, appAudioPatch, tileShadePatch)
    extendWith(XIAOMI_SCREEN_RECORDER_EXTENSION)
}
