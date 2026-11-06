package org.ungoogled.patches.samsungscreenrecorder.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.ungoogled.patches.samsungscreenrecorder.SamsungScreenRecorderConstants.COMPATIBILITY_SAMSUNG_SCREEN_RECORDER
import org.w3c.dom.Element

internal const val SAMSUNG_SCREEN_RECORDER_EXTENSION = "extensions/samsungscreenrecorder.mpe"

private const val EXT = "Lorg/ungoogled/samsungscreenrecorder"
private const val PROJECTION = "$EXT/Projection;"
private const val GUARDS = "$EXT/Guards;"
private const val APP = "Lcom/sec/app/screenrecorder"
private const val LAUNCHER = "$APP/activity/LauncherActivity;"
private const val SETTINGS = "$APP/activity/SettingActivity;"
private const val SERVICE = "$APP/encoder/RecordingThreadService;"
private const val AUDIO_RECORDER = "$APP/encoder/AudioRecorder;"
private const val MEDIA_UTIL = "$APP/util/MediaUtil;"
private const val FLOATING_VIEW = "$APP/view/FloatingButtonView;"
private const val ANALYTICS = "$APP/util/Analytics;"
private const val AUDIO_RECORD = "Landroid/media/AudioRecord;"

private const val LAUNCHER_NAME = "com.sec.app.screenrecorder.activity.LauncherActivity"
private const val SETTINGS_NAME = "com.sec.app.screenrecorder.activity.SettingActivity"
private const val VOICE_COMMAND_NAME = "com.sec.app.screenrecorder.activity.LcdGrayActivity"
private const val CONSENT_NAME = "org.ungoogled.samsungscreenrecorder.ConsentActivity"

private val log = java.util.logging.Logger.getLogger("SamsungScreenRecorder")

private fun Element.children(tag: String) =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

/**
 * One UI preinstalls the recorder as the system (sharedUserId android.uid.system), which only Samsung's platform key may
 * join, so anywhere else Android refuses the install. It has no launcher icon either (One UI starts it from a Quick
 * settings tile): "Record screen" gets one, and a long press on it opens the recorder's settings. Its "Record the touch
 * interactions" switch turns on Android's "show taps", a setting only the system may change, so it goes. An empty
 * screen that only answers voice-command intents (a One UI workaround) stops claiming them. Android's capture and
 * permission questions get a screen of their own (extension ConsentActivity).
 */
private val manifestPatch = resourcePatch(
    description = "Lets Samsung Screen Recorder install as a regular app and adds a launcher icon.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            if (root.getAttribute("android:sharedUserId") != "android.uid.system") {
                throw PatchException("the manifest no longer joins android.uid.system")
            }
            root.removeAttribute("android:sharedUserId")

            val activities = manifest.getElementsByTagName("activity").let { list ->
                (0 until list.length).map { list.item(it) as Element }
            }
            fun activity(name: String) = activities.firstOrNull { it.getAttribute("android:name") == name }
                ?: throw PatchException("$name not in the manifest")

            val launcher = activity(LAUNCHER_NAME)
            val main = launcher.children("intent-filter").firstOrNull { filter ->
                filter.children("action").any { it.getAttribute("android:name") == "android.intent.action.MAIN" }
            } ?: throw PatchException("$LAUNCHER_NAME has no MAIN intent filter")
            main.appendChild(manifest.createElement("category").apply {
                setAttribute("android:name", "android.intent.category.LAUNCHER")
            })
            launcher.appendChild(manifest.createElement("meta-data").apply {
                setAttribute("android:name", "android.app.shortcuts")
                setAttribute("android:resource", "@xml/ungoogled_shortcuts")
            })

            // The settings screen also opens from the gear in Android's App info.
            val settings = activity(SETTINGS_NAME)
            settings.appendChild(manifest.createElement("intent-filter").apply {
                appendChild(manifest.createElement("action").apply {
                    setAttribute("android:name", "android.intent.action.APPLICATION_PREFERENCES")
                })
                appendChild(manifest.createElement("category").apply {
                    setAttribute("android:name", "android.intent.category.DEFAULT")
                })
            })

            val voiceCommands = activity(VOICE_COMMAND_NAME)
            voiceCommands.children("intent-filter").forEach { voiceCommands.removeChild(it) }

            val application = manifest.getElementsByTagName("application").item(0) as Element
            // The system needs no permission to go foreground; an app does (the recording service).
            root.insertBefore(manifest.createElement("uses-permission").apply {
                setAttribute("android:name", "android.permission.FOREGROUND_SERVICE")
            }, application)
            application.appendChild(manifest.createElement("activity").apply {
                setAttribute("android:name", CONSENT_NAME)
                setAttribute("android:exported", "false")
                setAttribute("android:excludeFromRecents", "true")
                setAttribute("android:taskAffinity", "")
                setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
            })
        }

        // The launcher icon's long-press shortcut to the settings screen.
        decode("res/values/strings.xml", "res/xml/settings_preference.xml")
        document("res/values/strings.xml").use { strings ->
            strings.documentElement.appendChild(strings.createElement("string").apply {
                setAttribute("name", "ungoogled_settings")
                textContent = "Settings"
            })
        }
        get("res/xml/ungoogled_shortcuts.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
                    <shortcut android:shortcutId="settings" android:enabled="true"
                        android:icon="@drawable/icon_screen_recorder" android:shortcutShortLabel="@string/ungoogled_settings">
                        <intent android:action="com.sec.app.screenrecorder.START_SETTINGS"
                            android:targetPackage="com.sec.app.screenrecorder" android:targetClass="$SETTINGS_NAME" />
                    </shortcut>
                </shortcuts>
                """.trimIndent() + "\n",
            )
        }

        document("res/xml/settings_preference.xml").use { xml ->
            val touches = (0 until xml.documentElement.childNodes.length).map { xml.documentElement.childNodes.item(it) }
                .filterIsInstance<Element>().firstOrNull { it.getAttribute("android:key") == "show_touches" }
                ?: throw PatchException("no show_touches preference")
            xml.documentElement.removeChild(touches)
        }
    }
}

/**
 * Two icons are SPR files, Samsung's vector format that only One UI can draw (the settings screen's back arrow: the
 * screen crashed opening): they become vector drawables.
 */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        log.info("SPR drawables: $converted")
        // Measured against 4.0.23: tw_ic_ab_back_mtrl, screen_shot_play_btn.
        if (converted < 2) throw PatchException("only $converted SPR drawables found")
    }
}

/** Sends the recorder's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_SCREEN_RECORDER_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        log.info("One UI members: $calls calls, $fields fields")
        // Measured against 4.0.23: the floating control's window flag; the display type the service follows.
        if (calls < 1 || fields < 1) throw PatchException("only $calls One UI calls and $fields field accesses found")
    }
}

private fun BytecodePatchContext.method(type: String, name: String, predicate: (MutableMethod) -> Boolean = { true }) =
    (mutableClassDefByOrNull(type) ?: throw PatchException("$type not found")).methods
        .firstOrNull { it.name == name && predicate(it) } ?: throw PatchException("$type.$name not found")

private fun MutableMethod.indexOfCall(owner: String? = null, name: String, from: Int = 0): Int {
    val instructions = implementation!!.instructions.toList()
    for (i in from until instructions.size) {
        val ref = (instructions[i] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
        if (ref.name == name && (owner == null || ref.definingClass == owner)) return i
    }
    return -1
}

/**
 * As the system the recorder made its own virtual display of the screen (DisplayManager, CAPTURE_VIDEO_OUTPUT) and
 * never asked; as a regular app that call is refused, on One UI too. Now "Record screen" (the icon, the tile) first
 * asks for the storage and microphone permissions the recording needs, then opens Android's capture dialog, whose
 * Start starts the recording; the recording's display comes from that consent, and the capture ends with the
 * recording (or the recording ends when Android ends the capture). Extension Projection.
 */
private val recordingConsentPatch = bytecodePatch(
    description = "Asks for Android's screen capture consent before each recording.",
) {
    dependsOn(manifestPatch)
    extendWith(SAMSUNG_SCREEN_RECORDER_EXTENSION)

    execute {
        val started = redirectCalls(
            mapOf(
                "$LAUNCHER->startService(Landroid/content/Intent;)Landroid/content/ComponentName;" to
                    "$PROJECTION->startService(Landroid/content/Context;Landroid/content/Intent;)Landroid/content/ComponentName;",
                "Landroid/hardware/display/DisplayManager;->createVirtualDisplay(Ljava/lang/String;IIILandroid/view/Surface;I)Landroid/hardware/display/VirtualDisplay;" to
                    "$PROJECTION->createVirtualDisplay(Landroid/hardware/display/DisplayManager;Ljava/lang/String;IIILandroid/view/Surface;I)Landroid/hardware/display/VirtualDisplay;",
            ),
        )
        log.info("Recording service starts and virtual displays: $started")
        // Measured against 4.0.23: start and stop in LauncherActivity, the display in VideoRecorderTask.
        if (started != 3) throw PatchException("expected 3 service starts and virtual displays, found $started")

        // LauncherActivity.onResume, after the legal notice and before the overlay check: the missing permissions first.
        method(LAUNCHER, "onResume") { it.parameterTypes.isEmpty() }.apply {
            val overlay = indexOfCall("Landroid/provider/Settings;", "canDrawOverlays")
            if (overlay < 0) throw PatchException("LauncherActivity.onResume no longer checks the overlay permission")
            val result = getInstruction(overlay + 1)
            if (result.opcode != Opcode.MOVE_RESULT) throw PatchException("canDrawOverlays result not found")
            val register = (result as OneRegisterInstruction).registerA
            // Branches to the overlay check (from the legal notice's test) must run the permission check too.
            addInstructionsAtLabel(
                overlay,
                """
                    invoke-static {p0}, $PROJECTION->prepare(Landroid/app/Activity;)Z
                    move-result v$register
                """,
            )
            addInstructionsWithLabels(
                overlay + 2,
                """
                    if-eqz v$register, :check
                    invoke-virtual {p0}, $LAUNCHER->finish()V
                    return-void
                """,
                ExternalLabel("check", getInstruction(overlay + 2)),
            )
        }

        method(SERVICE, "onDestroy") { it.parameterTypes.isEmpty() }
            .addInstruction(0, "invoke-static {}, $PROJECTION->release()V")
    }
}

/**
 * "System sounds" came from the remote submix (CAPTURE_AUDIO_OUTPUT, the system only) and "System sounds and mic" from
 * the microphone alone. Now system sounds are Android's app audio capture through the recording's consent, and "and
 * mic" mixes the microphone in (extension Audio).
 */
private val appAudioPatch = bytecodePatch(
    description = "Records app audio and the microphone through Android's capture consent.",
) {
    dependsOn(recordingConsentPatch)
    extendWith(SAMSUNG_SCREEN_RECORDER_EXTENSION)

    execute {
        method(AUDIO_RECORDER, "run") { it.parameterTypes.isEmpty() }.apply {
            val instructions = implementation!!.instructions.toList()
            val create = instructions.indexOfFirst {
                it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == AUDIO_RECORD
            }
            if (create < 0) throw PatchException("AudioRecorder.run no longer makes an AudioRecord")
            val record = (instructions[create] as OneRegisterInstruction).registerA
            val init = instructions.indexOfFirst {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_DIRECT_RANGE && ref?.definingClass == AUDIO_RECORD && ref.name == "<init>" &&
                    ref.parameterTypes.joinToString("") == "IIIII"
            }
            val range = instructions.getOrNull(init) as? RegisterRangeInstruction
                ?: throw PatchException("new AudioRecord(source, rate, channels, encoding, size) not found")
            if (range.startRegister != record) throw PatchException("AudioRecord constructed in another register")
            replaceInstruction(
                init,
                "invoke-static/range { v${record + 1} .. v${record + 5} }, $EXT/Audio;->create(IIIII)$AUDIO_RECORD",
            )
            addInstruction(init + 1, "move-result-object v$record")
            // The register stays unset until the factory's answer.
            replaceInstruction(create, "const/4 v$record, 0x0")
        }
    }
}

/**
 * Windows and settings only the system may use. The "saving" dialog was a system alert window (refused to apps since
 * Android 8, so stopping a recording crashed): it becomes an app overlay, like the floating control. Writes to
 * Android's "show taps" setting, the One UI-only MediaStore columns of the saved video and One UI screens the phone
 * may not have can no longer crash the recorder (extension Guards).
 */
private val systemCallsPatch = bytecodePatch(
    description = "Keeps the recorder's system-only windows and settings from crashing it.",
) {
    extendWith(SAMSUNG_SCREEN_RECORDER_EXTENSION)

    execute {
        method(SERVICE, "createLoadingDialog").apply {
            val setType = indexOfCall("Landroid/view/Window;", "setType")
            val type = getInstruction(setType - 1)
            if (setType < 1 || type.opcode != Opcode.CONST_16) throw PatchException("loading dialog window type not found")
            // TYPE_SYSTEM_ALERT -> TYPE_APPLICATION_OVERLAY.
            replaceInstruction(setType - 1, "const/16 v${(type as OneRegisterInstruction).registerA}, 0x7f6")
        }

        val guarded = redirectCalls(
            mapOf(
                "Landroid/provider/Settings\$System;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z" to
                    "$GUARDS->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z",
                "$LAUNCHER->startActivity(Landroid/content/Intent;)V" to
                    "$GUARDS->startActivity(Landroid/app/Activity;Landroid/content/Intent;)V",
                "$SETTINGS->startActivity(Landroid/content/Intent;)V" to
                    "$GUARDS->startActivity(Landroid/app/Activity;Landroid/content/Intent;)V",
                "$APP/view/SecureView;->show()V" to "$GUARDS->show(Landroid/app/Dialog;)V",
            ),
        )
        log.info("Guarded settings writes and screens: $guarded")
        // Measured against 4.0.23: 4 show-taps writes, 4 screens, 1 password cover.
        if (guarded != 9) throw PatchException("expected 9 settings writes, screens and covers, found $guarded")

        fun redirectIn(type: String, key: String, target: String): Int {
            var moved = 0
            mutableClassDefBy(type).methods.forEach { method ->
                method.implementation?.instructions?.toList()?.forEachIndexed { index, instruction ->
                    val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
                    if ("${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}" != key) return@forEachIndexed
                    val registers = (instruction as? FiveRegisterInstruction)?.let {
                        listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
                    } ?: throw PatchException("unexpected ${instruction.opcode} in $type.${method.name}")
                    method.replaceInstruction(index, "invoke-static { ${registers.joinToString(", ") { "v$it" }} }, $target")
                    moved++
                }
            }
            return moved
        }
        val inserts = redirectIn(
            MEDIA_UTIL,
            "Landroid/content/ContentResolver;->insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
            "$GUARDS->insert(Landroid/content/ContentResolver;Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
        )
        val views = redirectIn(
            FLOATING_VIEW,
            "Landroid/view/WindowManager;->addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
            "$GUARDS->addView(Landroid/view/WindowManager;Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
        )
        if (inserts != 1 || views != 1) throw PatchException("expected 1 MediaStore insert and 1 floating window, found $inserts and $views")
    }
}

/**
 * Samsung Analytics. As the system the recorder had network access; a copy anyone can install has no INTERNET
 * permission, and the analytics library asks Android for the network state, which it may not, so starting a
 * recording crashed. Nothing asks the user about these reports (the legal notice doesn't mention them), so they stay
 * off rather than going to Samsung unasked.
 */
private val analyticsPatch = bytecodePatch(
    description = "Turns off Samsung Analytics.",
) {
    execute {
        val analytics = mutableClassDefByOrNull(ANALYTICS) ?: throw PatchException("$ANALYTICS not found")
        val reports = analytics.methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.name != "<clinit>" && it.implementation != null
        }
        // Measured against 4.0.23: init, sendEventLog, sendPageLog, sendSettingLog.
        if (reports.size < 4) throw PatchException("only ${reports.size} analytics methods found")
        reports.forEach { it.addInstructionsAtLabel(0, "return-void") }
    }
}

/**
 * Off One UI Screen Recorder can't install (it joins the system's shared user), reads One UI classes
 * (SemFloatingFeature, SemSystemProperties) and records the way only the system may (see the patches above). The
 * extension carries stand-ins for the 3 One UI classes it uses (generated by .github/scripts/samsung-standins); on a
 * Samsung phone the framework's own classes load first, so they never run there.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Screen Recorder installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_SCREEN_RECORDER)
    dependsOn(manifestPatch, sprDrawablesPatch, oneUiMembersPatch, recordingConsentPatch, appAudioPatch, systemCallsPatch, analyticsPatch)
    extendWith(SAMSUNG_SCREEN_RECORDER_EXTENSION)
}
