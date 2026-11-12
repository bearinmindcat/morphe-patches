package org.ungoogled.patches.samsungcapture.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectMembers
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsungcapture.SamsungCaptureConstants.COMPATIBILITY_SAMSUNG_CAPTURE
import org.w3c.dom.Element

internal const val SAMSUNG_CAPTURE_EXTENSION = "extensions/samsungcapture.mpe"

private const val EXT = "Lorg/ungoogled/samsungcapture"
private const val PROJECTION = "$EXT/Projection;"
private const val APP = "Lcom/samsung/android/app/smartcapture"
private const val SEP = "$APP/baseutil/sep/SepWrapper"
private const val RECORDER = "$APP/screenrecorder"
private const val RECORDER_SERVICE = "$RECORDER/ScreenRecorderService;"
private const val RECORDER_CONTROLLER = "$RECORDER/ScreenRecorderController;"

private const val SETTINGS_NAME = "com.samsung.android.app.settings.SettingsActivity"
private const val CONSENT_NAME = "org.ungoogled.samsungcapture.ConsentActivity"
private const val EDIT_NAME = "org.ungoogled.samsungcapture.EditActivity"

private val log = java.util.logging.Logger.getLogger("SamsungCapture")

private fun Element.children(tag: String) =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

/**
 * One UI has no launcher icon for Samsung capture: its settings open from Settings and recording from a Quick settings
 * tile. "Samsung capture" now opens its settings (screenshot and screen recording options), and a long press on it
 * offers "Record screen" and "Edit" (Samsung's screenshot editor on a picture). The settings screen is on from the
 * start (One UI turns it on at boot where the phone has a screen recorder). Android's capture, overlay and
 * notification questions get a screen of their own (extension ConsentActivity); other apps' "Edit" and "Share" offer
 * the editor (extension EditActivity).
 */
private val manifestPatch = resourcePatch(
    description = "Adds a launcher icon, the consent screen and the editor entry to Samsung capture.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            if (root.getAttribute("package") != COMPATIBILITY_SAMSUNG_CAPTURE.packageName) {
                throw PatchException("not Samsung capture's manifest")
            }
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activities = manifest.getElementsByTagName("activity").let { list ->
                (0 until list.length).map { list.item(it) as Element }
            }
            val settings = activities.firstOrNull { it.getAttribute("android:name") == SETTINGS_NAME }
                ?: throw PatchException("$SETTINGS_NAME not in the manifest")
            settings.setAttribute("android:enabled", "true")
            settings.appendChild(manifest.createElement("intent-filter").apply {
                appendChild(manifest.createElement("action").apply { setAttribute("android:name", "android.intent.action.MAIN") })
                appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.LAUNCHER") })
            })
            settings.appendChild(manifest.createElement("meta-data").apply {
                setAttribute("android:name", "android.app.shortcuts")
                setAttribute("android:resource", "@xml/ungoogled_shortcuts")
            })

            application.appendChild(manifest.createElement("activity").apply {
                setAttribute("android:name", CONSENT_NAME)
                setAttribute("android:exported", "false")
                setAttribute("android:excludeFromRecents", "true")
                setAttribute("android:taskAffinity", "")
                setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
            })
            application.appendChild(manifest.createElement("activity").apply {
                setAttribute("android:name", EDIT_NAME)
                setAttribute("android:exported", "true")
                setAttribute("android:excludeFromRecents", "true")
                setAttribute("android:taskAffinity", "")
                setAttribute("android:label", "@string/notification_edit")
                setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                for (action in listOf("android.intent.action.EDIT", "android.intent.action.SEND")) {
                    appendChild(manifest.createElement("intent-filter").apply {
                        appendChild(manifest.createElement("action").apply { setAttribute("android:name", action) })
                        appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.DEFAULT") })
                        appendChild(manifest.createElement("data").apply { setAttribute("android:mimeType", "image/*") })
                    })
                }
            })
        }

        decode("res/values/strings.xml")
        document("res/values/strings.xml").use { strings ->
            strings.documentElement.appendChild(strings.createElement("string").apply {
                setAttribute("name", "ungoogled_overlay_needed")
                textContent = "Allow Samsung capture to appear on top to record the screen."
            })
        }
        val pkg = COMPATIBILITY_SAMSUNG_CAPTURE.packageName
        get("res/xml/ungoogled_shortcuts.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
                    <shortcut android:shortcutId="record" android:enabled="true"
                        android:icon="@drawable/samsung_capture_icon" android:shortcutShortLabel="@string/record_screen">
                        <intent android:action="com.samsung.android.app.screenrecorder.ACTION_START"
                            android:targetPackage="$pkg" android:targetClass="$CONSENT_NAME" />
                    </shortcut>
                    <shortcut android:shortcutId="edit" android:enabled="true"
                        android:icon="@drawable/samsung_capture_icon" android:shortcutShortLabel="@string/notification_edit">
                        <intent android:action="android.intent.action.MAIN"
                            android:targetPackage="$pkg" android:targetClass="$EDIT_NAME" />
                    </shortcut>
                </shortcuts>
                """.trimIndent() + "\n",
            )
        }
    }
}

/** Samsung's SPR icons (a vector format only One UI draws) become vector drawables. */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        log.info("SPR drawables: $converted")
    }
}

/**
 * Sends Samsung capture's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers), and
 * its system service lookups through Services.
 */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_CAPTURE_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        val services = redirectSystemServices("$EXT/Services;")
        log.info("One UI members: $calls calls, $fields fields; service lookups: $services")
        // Measured against 6.2.12: 141 calls, 22 field accesses.
        if (calls < 50 || fields < 5) throw PatchException("only $calls One UI calls and $fields field accesses found")
    }
}

/** One UI's own framework classes the app uses (stand-ins in the extension off One UI, Samsung's own on a Galaxy). */
private val ONE_UI_CLASS = Regex(
    "^L(com/samsung/(advmmutils|audio|objectremovallib)/|com/samsung/android/(app/Sem|cocktailbar/|content/smartclip/|" +
        "cover/|desktopmode/|feature/|infoextraction/|knox/|media/|os/|sume/|tsp/|view/Sem|widget/Sem)|" +
        "android/[a-z/]+/(Sem|[A-Za-z]+\\\$Sem))",
)

/**
 * On a Galaxy phone One UI's framework classes are Samsung's own, and several of their calls answer only Samsung's
 * signature or privileged permissions: closing the shade (SemStatusBarManager, STATUS_BAR), watching tasks
 * (SemActivityTaskManager), performance boosts (SemDvfsManager), playback-capture volume (SemHiddenSoundManager), ...
 * Each refusal (SecurityException) stopped the app; off One UI the stand-ins never refuse. Every call into those
 * classes now goes through OneUi (see redirectMembers), which answers a refused call as if the class had nothing to
 * offer. Classes the app itself extends or implements keep their direct calls (super calls must stay).
 */
private val oneUiClassesPatch = bytecodePatch(
    description = "Keeps One UI's framework classes from stopping the app where they refuse it.",
) {
    dependsOn(oneUiMembersPatch)
    extendWith(SAMSUNG_CAPTURE_EXTENSION)

    execute {
        fun framework(type: String) = ONE_UI_CLASS.containsMatchIn(type)
        val inherited = mutableSetOf<String>()
        val virtual = mutableSetOf<String>()
        val superCalls = mutableSetOf<String>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/") || framework(c.type)) return@classDefForEach
            (listOfNotNull(c.superclass) + c.interfaces).filter(::framework).forEach { inherited += it }
            for (m in c.methods) {
                for (insn in m.implementation?.instructions ?: continue) {
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (!framework(ref.definingClass) || ref.name == "<init>") continue
                    val key = "${ref.definingClass}->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
                    when (insn.opcode) {
                        Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE, Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE -> superCalls += key
                        else -> virtual += key
                    }
                }
            }
        }
        val keys = virtual.filter { key -> key !in superCalls && inherited.none { key.startsWith("$it->") } }.toSet()
        val (calls, _) = redirectMembers("$EXT/OneUi;", "$EXT/SamsungClasses;", keys) { _, _ -> false }
        log.info("One UI framework class calls: $calls (${keys.size} members)")
        // Measured against 6.2.12: 516 calls to 189 members.
        if (calls < 300) throw PatchException("only $calls calls into One UI framework classes found")
    }
}

private fun BytecodePatchContext.method(type: String, name: String, predicate: (MutableMethod) -> Boolean = { true }) =
    (mutableClassDefByOrNull(type) ?: throw PatchException("$type not found")).methods
        .firstOrNull { it.name == name && predicate(it) } ?: throw PatchException("$type.$name not found")

/** Replaces a static method's body with a call to [target] taking the same parameters and returning its answer. */
private fun MutableMethod.forwardTo(target: String, returnKind: String) {
    val params = parameterTypes.indices.joinToString(", ") { "p$it" }
    val result = when (returnKind) {
        "object" -> "move-result-object p0\nreturn-object p0"
        "int" -> "move-result p0\nreturn p0"
        else -> throw IllegalArgumentException(returnKind)
    }
    addInstructions(0, "invoke-static { $params }, $target\n$result")
}

/** Redirects every call to [key] inside [type]'s methods to the static [target]; returns how many. */
private fun BytecodePatchContext.redirectIn(type: String, key: String, target: String): Int {
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

/**
 * The screen recorder. One UI turns it on only where the phone says it has one (a device feature off One UI and on
 * One UI Core phones): it is on. As a privileged system app it took its screen capture from
 * semGetMediaProjection (MANAGE_MEDIA_PROJECTION) and app sound from a Samsung audio source (CAPTURE_AUDIO_OUTPUT),
 * asking nobody; both are refused to a regular app, on One UI too. Now a recording (the Quick settings tile, the launcher
 * shortcut, or anything else that starts it) first asks for "appear on top" (its controls float over other apps) and
 * Android's capture consent, whose Start starts the recorder; the recording's display and its "Media sounds" come from
 * that consent (AudioPlaybackCapture), and the capture ends with the recorder. Its foreground service names its types,
 * as Android 14 requires.
 */
private val recordingPatch = bytecodePatch(
    description = "Records the screen and app audio through Android's capture consent.",
) {
    dependsOn(manifestPatch)
    extendWith(SAMSUNG_CAPTURE_EXTENSION)

    execute {
        method("$APP/baseutil/sep/SemFloatingFeatureWrapper;", "getBoolean")
            .forwardTo("$EXT/Features;->getBoolean(Ljava/lang/String;)Z", "int")

        val projections = mutableClassDefByOrNull("$SEP\$MediaProjectionManager;")?.methods
            ?.filter { it.name == "semGetMediaProjection" } ?: throw PatchException("SepWrapper.MediaProjectionManager not found")
        if (projections.size != 2) throw PatchException("expected 2 semGetMediaProjection wrappers, found ${projections.size}")
        projections.forEach { wrapper ->
            val target = if (wrapper.parameterTypes.size == 2) {
                "$PROJECTION->get(Landroid/media/projection/MediaProjectionManager;I)Landroid/media/projection/MediaProjection;"
            } else {
                "$PROJECTION->get(Landroid/media/projection/MediaProjectionManager;)Landroid/media/projection/MediaProjection;"
            }
            wrapper.forwardTo(target, "object")
        }

        method("$SEP\$MediaRecorder;", "semGetInputSource").forwardTo("$EXT/Audio;->inputSource(I)I", "int")
        method("$RECORDER/util/AudioUtil;", "isSystemAudioSupported")
            .forwardTo("$EXT/Audio;->isSystemAudioSupported(Landroid/content/Context;)Z", "int")

        val moved = redirectCalls(
            mapOf(
                "$RECORDER/util/ScreenRecorderUtils;->startRecorderService(Landroid/content/Context;Z)V" to
                    "$PROJECTION->startRecorderService(Landroid/content/Context;Z)V",
                "Landroid/media/AudioRecord\$Builder;->setAudioSource(I)Landroid/media/AudioRecord\$Builder;" to
                    "$EXT/Audio;->setAudioSource(Landroid/media/AudioRecord\$Builder;I)Landroid/media/AudioRecord\$Builder;",
            ),
        )
        log.info("Recorder starts and audio sources: $moved")
        // Measured against 6.2.12: tile, side button, routine; 4 audio recorders.
        if (moved < 7) throw PatchException("expected 3 recorder starts and 4 audio sources, found $moved")

        val foreground = redirectIn(
            RECORDER_CONTROLLER,
            "Landroid/app/Service;->startForeground(ILandroid/app/Notification;)V",
            "$PROJECTION->startForeground(Landroid/app/Service;ILandroid/app/Notification;)V",
        )
        if (foreground != 1) throw PatchException("expected 1 recorder startForeground, found $foreground")

        // Before recording it checks for secure windows with a screenshot only the system may take.
        val secureCheck = redirectIn(
            RECORDER_CONTROLLER,
            "$APP/baseutil/view/CaptureUtils;->captureScreen(Ljava/lang/String;Landroid/content/Context;FI)Landroid/graphics/Bitmap;",
            "$PROJECTION->secureLayerCheck(Ljava/lang/String;Landroid/content/Context;FI)Landroid/graphics/Bitmap;",
        )
        if (secureCheck != 1) throw PatchException("expected 1 secure window check, found $secureCheck")

        method(RECORDER_SERVICE, "onStartCommand").apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0, p1 }, $PROJECTION->intercept(Landroid/app/Service;Landroid/content/Intent;)Z
                    move-result v0
                    if-eqz v0, :start
                    const/4 v0, 0x2
                    return v0
                """,
                ExternalLabel("start", getInstruction(0)),
            )
        }
        method(RECORDER_SERVICE, "onDestroy").addInstruction(0, "invoke-static {}, $PROJECTION->release()V")

        // Partial-screen recording (One UI 8.5 and newer) first fits its frame to what is on screen, from a screenshot
        // only the system may take: off One UI the screenshot is missing, on a Galaxy phone refused, and the empty
        // picture stopped the recorder. The frame now starts where Samsung puts it without that hint.
        method("$RECORDER/partial/PartialRecordingController;", "extractHeroRect") { it.parameterTypes.isEmpty() }
            .addInstruction(0, "return-void")
    }
}

/**
 * The recorder's controls, countdown, drawing layer and sound question, the editor's capture effect and several
 * dialogs use window types only the system may add (looked up by name or set on the dialog); a regular app draws over
 * other apps with TYPE_APPLICATION_OVERLAY, and a dialog over its own screen needs no special type (extension Windows).
 * The floating windows also limit their touches to their controls through a field Android refuses an app, so the
 * recorder's toolbar window took every touch on the screen; the same limit is set through the insets an app may set.
 */
private val windowsPatch = bytecodePatch(
    description = "Moves Samsung capture's system windows to app overlays.",
) {
    extendWith(SAMSUNG_CAPTURE_EXTENSION)

    execute {
        method("$APP/baseutil/device/DeviceUtils;", "getWindowType") { it.parameterTypes == listOf("Ljava/lang/String;") }
            .forwardTo("$EXT/Windows;->type(Ljava/lang/String;)I", "int")
        method("$SEP\$Context;", "createWindowContext")
            .forwardTo("$EXT/Windows;->createWindowContext(Landroid/content/Context;ILandroid/os/Bundle;)Landroid/content/Context;", "object")
        method("$APP/screenwriter/utils/ScreenWriterUtils;", "getScreenshotAnimationWindowType") { it.parameterTypes.isEmpty() }
            .addInstructions(0, "const/16 v0, 0x7f6\nreturn v0")

        val dialogs = redirectCalls(
            mapOf("Landroid/view/Window;->setType(I)V" to "$EXT/Windows;->setType(Landroid/view/Window;I)V"),
        )
        // Measured against 6.2.12: 10 (the editor's "All files access" question among them).
        if (dialogs < 5) throw PatchException("only $dialogs window type changes found")

        // Touchable areas of the floating windows (see Windows.touchableRegion).
        val reflections = "$APP/baseutil/reflections"
        val listenerBase = "$reflections/ViewTreeObserverOnComputeInternalInsetsListenerReflection;"
        val info = "$reflections/ViewTreeObserverInternalInsetsInfoReflection;"
        val listeners = mutableListOf<String>()
        classDefForEach { if (it.superclass == listenerBase) listeners += it.type }
        var touchable = 0
        for (type in listeners) {
            val compute = mutableClassDefBy(type).methods.firstOrNull { it.name == "onComputeInternalInsets" } ?: continue
            val moved = redirectIn(
                type,
                "$info->getTouchableRegion(Ljava/lang/Object;)Landroid/graphics/Region;",
                "$EXT/Windows;->touchableRegionOf(Ljava/lang/Object;Ljava/lang/Object;)Landroid/graphics/Region;",
            ) + redirectIn(
                type,
                "$info->setTouchableInsets(Ljava/lang/Object;I)V",
                "$EXT/Windows;->setTouchableInsets(Ljava/lang/Object;Ljava/lang/Object;I)V",
            )
            if (moved == 0) continue
            compute.addInstruction(0, "invoke-static { p0, p1 }, $EXT/Windows;->beginInsets(Ljava/lang/Object;Ljava/lang/Object;)V")
            touchable++
        }
        // The drawing layer goes under the toolbar again (see Windows.raiseRecorderToolbar).
        val toolbarClass = mutableClassDefByOrNull("$RECORDER/view/FloatingButtonView;")
            ?: throw PatchException("FloatingButtonView not found")
        if (toolbarClass.fields.none { it.name == "mToolbarLayout" } || toolbarClass.fields.none { it.name == "mWindowManager" } ||
            toolbarClass.methods.none { it.name == "initComputeInternalInsetsListener" && it.parameterTypes.isEmpty() }
        ) {
            throw PatchException("FloatingButtonView no longer has its toolbar window fields")
        }
        method("$RECORDER/view/AnnotationView;", "show") { it.parameterTypes.isEmpty() }.apply {
            val instructions = implementation!!.instructions.toList()
            val add = instructions.indexOfLast {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref?.name == "addView" && ref.parameterTypes.size == 2
            }
            if (add < 0) throw PatchException("AnnotationView.show no longer adds its window")
            addInstruction(add + 1, "invoke-static {}, $EXT/Windows;->raiseRecorderToolbar()V")
        }

        // Window frames: other apps take touches beside the recorder's windows (see Windows.addFittedView).
        val viewManager = "Landroid/view/ViewManager;"
        val layoutShape = "(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V"
        val windowOwners = mapOf(
            "$RECORDER/view/FloatingButtonView" to "addFittedView",
            "$RECORDER/profile/view/camera/FloatingCameraView" to "addFittedView",
            "$RECORDER/view/RecordedAreaBoundary" to "addFittedView",
            "$RECORDER/view/CountdownView" to "addPassThroughView",
            "$RECORDER/partial/PartialRecordingController" to "addPassThroughView",
        )
        val windowCalls = mutableMapOf<String, Int>()
        for ((owner, add) in windowOwners) {
            val types = mutableListOf<String>()
            classDefForEach { if (it.type == "$owner;" || it.type.startsWith("$owner\$")) types += it.type }
            if (types.isEmpty()) throw PatchException("$owner not found")
            for (type in types) {
                mapOf(
                    add to ("$viewManager->addView$layoutShape" to "$EXT/Windows;->$add($viewManager${layoutShape.substring(1)}"),
                    "update" to ("$viewManager->updateViewLayout$layoutShape" to "$EXT/Windows;->updateViewLayout($viewManager${layoutShape.substring(1)}"),
                    "remove" to ("$viewManager->removeView(Landroid/view/View;)V" to "$EXT/Windows;->removeView(${viewManager}Landroid/view/View;)V"),
                    "removeNow" to ("Landroid/view/WindowManager;->removeViewImmediate(Landroid/view/View;)V" to
                        "$EXT/Windows;->removeViewImmediate(Landroid/view/WindowManager;Landroid/view/View;)V"),
                ).forEach { (kind, call) -> windowCalls[kind] = (windowCalls[kind] ?: 0) + redirectIn(type, call.first, call.second) }
            }
        }
        log.info("Window calls: $windowCalls")
        // Measured against 6.2.12: toolbar, pointer tour, selfie view, flex-mode boundary; countdown, partial screen.
        if ((windowCalls["addFittedView"] ?: 0) < 3 || (windowCalls["addPassThroughView"] ?: 0) < 2) {
            throw PatchException("recorder windows not found: $windowCalls")
        }

        log.info("Touchable-area listeners: $touchable of ${listeners.size}")
        // Measured against 6.2.12: recorder toolbar, countdown, selfie view, partial recording, pin, GIF crop.
        if (touchable < 3) throw PatchException("only $touchable touchable-area listeners found")

    }
}

/**
 * Calls One UI allows its preinstalled Samsung capture and Android refuses a regular app (extension Guards):
 * CLOSE_SYSTEM_DIALOGS broadcasts (system only since Android 12; the editor sent one opening) are skipped, and the
 * recorder's call-state watch (READ_PHONE_STATE; starting a recording crashed) is skipped where it is refused, and
 * queries, writes and watches of One UI's own providers the phone lacks or refuses (Samsung's media provider: the
 * editor crashed after saving) are skipped (extension Providers). On a Galaxy phone One UI's services and providers
 * are there but answer only Samsung's own signature: a refused start, bind, query or watch now counts as absent.
 */
private val guardsPatch = bytecodePatch(
    description = "Keeps Samsung capture's system-only calls from crashing it.",
) {
    extendWith(SAMSUNG_CAPTURE_EXTENSION)

    execute {
        val guarded = redirectCalls(
            mapOf(
                "Landroid/content/Context;->sendBroadcast(Landroid/content/Intent;)V" to
                    "$EXT/Guards;->sendBroadcast(Landroid/content/Context;Landroid/content/Intent;)V",
                "Landroid/content/Context;->sendBroadcast(Landroid/content/Intent;Ljava/lang/String;)V" to
                    "$EXT/Guards;->sendBroadcast(Landroid/content/Context;Landroid/content/Intent;Ljava/lang/String;)V",
                "Landroid/telephony/TelephonyManager;->listen(Landroid/telephony/PhoneStateListener;I)V" to
                    "$EXT/Guards;->listen(Landroid/telephony/TelephonyManager;Landroid/telephony/PhoneStateListener;I)V",
                "Landroid/telephony/TelephonyManager;->getCallState()I" to
                    "$EXT/Guards;->getCallState(Landroid/telephony/TelephonyManager;)I",
            ),
        )
        val context = "Landroid/content/Context;"
        val services = redirectCalls(
            listOf(
                "startService(Landroid/content/Intent;)Landroid/content/ComponentName;",
                "startForegroundService(Landroid/content/Intent;)Landroid/content/ComponentName;",
                "bindService(Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z",
                "bindService(Landroid/content/Intent;ILjava/util/concurrent/Executor;Landroid/content/ServiceConnection;)Z",
                "bindServiceAsUser(Landroid/content/Intent;Landroid/content/ServiceConnection;ILandroid/os/UserHandle;)Z",
            ).associate { shape ->
                "$context->$shape" to "$EXT/Guards;->${shape.substringBefore('(')}($context${shape.substringAfter('(')}"
            },
        )
        log.info("Service starts and binds: $services")
        // Measured against 6.2.12: 44 service starts and binds.
        if (services < 30) throw PatchException("only $services service starts and binds found")

        val resolver = "Landroid/content/ContentResolver;"
        val providers = redirectCalls(
            listOf(
                "insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
                "update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I",
                "delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I",
                "call(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
                "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
                "query(Landroid/net/Uri;[Ljava/lang/String;Landroid/os/Bundle;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
                "registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;)V",
            ).associate { shape ->
                "$resolver->$shape" to "$EXT/Providers;->${shape.substringBefore('(')}($resolver${shape.substringAfter('(')}"
            },
        )
        log.info("Provider writes and calls: $providers")
        // Measured against 6.2.12: 158 (21 inserts, 12 updates, 10 deletes, 48 calls, the rest queries and watches).
        if (providers < 100) throw PatchException("only $providers provider queries, writes, calls and watches found")
        log.info("Guarded calls: $guarded")
        // Measured against 6.2.12: 50 broadcasts, 6 call-state watches, 4 call-state reads.
        if (guarded < 30) throw PatchException("only $guarded guarded calls found")
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
 * Samsung capture keeps its options (recorder sound and quality, selfie view size, screenshot format and toolbar) in
 * Android's settings tables, which only the system may write with One UI's keys (the settings screen crashed opening),
 * and which One UI lets any app write in System, so a copy next to Samsung's changed Samsung's own options. Every
 * settings call goes through LocalSettings, which keeps this copy's options in its own files.
 *
 * The options only Samsung's preinstalled copy can carry out are hidden (extension SystemOnly): the screenshot options,
 * which One UI's SystemUI reads when it takes a screenshot, and "Show taps and touches", which One UI draws through a
 * hidden InputManager call for Samsung's system app only (an app sees no touches outside its own windows).
 */
private val settingsPatch = bytecodePatch(
    description = "Keeps Samsung capture's own settings in the app.",
) {
    extendWith(SAMSUNG_CAPTURE_EXTENSION)

    execute {
        val calls = buildMap {
            for (table in listOf("Global", "Secure", "System")) {
                for ((shape, suffix) in SETTINGS_SHAPES) {
                    put(
                        "Landroid/provider/Settings\$$table;->$shape",
                        "$EXT/LocalSettings;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}",
                    )
                }
            }
        }
        val moved = redirectCalls(calls)
        log.info("Settings calls: $moved")
        // Measured against 6.2.12: 212.
        if (moved < 100) throw PatchException("only $moved settings calls found")

        // The settings screen: hidden right after its options are read from their XML.
        val preferences = "Landroidx/preference"
        if (mutableClassDefByOrNull("$preferences/PreferenceFragmentCompat;")?.methods
                ?.none { it.name == "findPreference" && it.parameterTypes == listOf("Ljava/lang/CharSequence;") } != false ||
            mutableClassDefByOrNull("$preferences/Preference;")?.methods
                ?.none { it.name == "setVisible" && it.parameterTypes == listOf("Z") } != false
        ) {
            throw PatchException("androidx preference no longer has findPreference(CharSequence) and setVisible(boolean)")
        }
        method("Lcom/samsung/android/app/settings/SettingsFragment;", "onCreate") {
            it.parameterTypes == listOf("Landroid/os/Bundle;")
        }.apply {
            val read = implementation!!.instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "addPreferencesFromResource"
            }
            if (read < 0) throw PatchException("SettingsFragment.onCreate no longer reads its options from XML")
            addInstruction(read + 1, "invoke-static/range { p0 .. p0 }, $EXT/SystemOnly;->hidePreferences(Ljava/lang/Object;)V")
        }

        // The recorder's sound question before a recording also offers "Show taps and touches".
        val views = redirectIn(
            RECORDER_CONTROLLER,
            "Landroid/view/View;->findViewById(I)Landroid/view/View;",
            "$EXT/SystemOnly;->findViewById(Landroid/view/View;I)Landroid/view/View;",
        )
        val showTaps = mutableClassDefBy(RECORDER_CONTROLLER).methods.any { m ->
            m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)
                    ?.string == "show_taps_and_touches"
            } == true
        }
        log.info("Recorder view lookups: $views")
        // Measured against 6.2.12: 18.
        if (views < 2 || !showTaps) throw PatchException("the recorder's sound question no longer has its show taps switch")
    }
}

/**
 * Google's ML Kit (the text and object recognition Samsung capture bundles) uploads usage reports through Google's
 * data transport in the background (JobScheduler, Firebase logging), unasked. Without a registered upload backend
 * the transport drops its queued reports instead.
 */
private val reportUploadsPatch = resourcePatch(
    description = "Removes the background upload of ML Kit usage reports.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val services = manifest.getElementsByTagName("service").let { list -> (0 until list.length).map { list.item(it) as Element } }
            val discovery = services.firstOrNull {
                it.getAttribute("android:name") == "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery"
            } ?: throw PatchException("Google data transport service not found")
            val backends = discovery.children("meta-data").filter { it.getAttribute("android:name").startsWith("backend:") }
            if (backends.isEmpty()) throw PatchException("no data transport backend registered")
            backends.forEach { discovery.removeChild(it) }
        }
    }
}

/**
 * Samsung Analytics: nothing in Samsung capture asks the user about these usage reports off One UI, so they stay off
 * rather than going to Samsung unasked; ML Kit's usage reports to Google likewise (see reportUploadsPatch). The
 * "About Samsung capture" page asked Samsung's update server (vas.samsungapps.com) for a newer version each time it
 * opened, sending the phone's model and carrier; the newer version would be Samsung's, which can't replace this copy,
 * so the check answers "up to date".
 */
private val telemetryPatch = bytecodePatch(
    description = "Turns off Samsung Analytics, ML Kit's usage reports and the update check.",
) {
    dependsOn(reportUploadsPatch)

    execute {
        method("$APP/baseutil/analytics/SmartCaptureSamsungAnalyticsUtils;", "setConfiguration")
            .addInstruction(0, "return-void")
        // Unconfigured, the SDK's tracker lookup is an assertion that throws where the ROM is not a "user" build
        // (LineageOS and other userdebug ROMs: the settings screen, recorder and editor stopped on their first log).
        // Every log call asks for the tracker first, so those calls return at once.
        val tracker = mutableListOf<Method>()
        classDefForEach { c ->
            c.methods.filterTo(tracker) { m ->
                m.parameterTypes.isEmpty() && m.returnType == c.type && m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "call after setConfiguration() method"
                } == true
            }
        }
        val lookup = tracker.singleOrNull() ?: throw PatchException("Samsung Analytics' tracker lookup not found (${tracker.size})")
        val loggers = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type == lookup.definingClass) return@classDefForEach
            c.methods.filter { m ->
                m.implementation?.instructions?.any {
                    val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ref != null && ref.definingClass == lookup.definingClass && ref.name == lookup.name &&
                        ref.parameterTypes.isEmpty() && ref.returnType == lookup.returnType
                } == true
            }.forEach { loggers += c.type to it }
        }
        loggers.forEach { (type, m) ->
            if (m.returnType != "V") throw PatchException("Samsung Analytics log call $type.${m.name} returns ${m.returnType}")
            val shape = m.parameterTypes.map(CharSequence::toString)
            method(type, m.name) { it.parameterTypes.map(CharSequence::toString) == shape && it.returnType == "V" }
                .addInstruction(0, "return-void")
        }
        log.info("Samsung Analytics log calls: ${loggers.size}")
        // Measured against 6.2.12: 5 (screen, event and status logs).
        if (loggers.size < 3) throw PatchException("only ${loggers.size} Samsung Analytics log calls found")
        val result = "Lcom/samsung/android/app/settings/updatechecker/CheckForUpdates\$PackageCheckResult;"
        method("Lcom/samsung/android/app/settings/updatechecker/CheckForUpdates;", "createPackageCheckResult")
            .addInstructions(
                0,
                """
                    new-instance v0, $result
                    invoke-direct { v0 }, $result-><init>()V
                    const/4 v1, 0x1
                    iput v1, v0, $result->stubCheckResult:I
                    return-object v0
                """,
            )
    }
}

/**
 * Off One UI Samsung capture dies at once on One UI classes (SemSystemProperties, SemFloatingFeature, ...). The
 * extension carries stand-ins for the One UI classes it uses (generated by .github/scripts/samsung-standins); on a
 * Samsung phone the framework's own classes load first, so they never run there. Screen recording (with app audio,
 * the microphone, pause, drawing and the selfie view) and the screenshot editor work; what One UI's SystemUI drives
 * (the toolbar after a screenshot, scroll capture, Smart select) and Samsung's AI features stay off.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Capture's screen recorder and editor usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_CAPTURE)
    dependsOn(manifestPatch, sprDrawablesPatch, oneUiMembersPatch, oneUiClassesPatch, settingsPatch, recordingPatch, windowsPatch, guardsPatch, telemetryPatch)
    extendWith(SAMSUNG_CAPTURE_EXTENSION)
}
