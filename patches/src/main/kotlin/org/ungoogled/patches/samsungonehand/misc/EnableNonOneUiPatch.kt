package org.ungoogled.patches.samsungonehand.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsungonehand.SamsungOneHandConstants.COMPATIBILITY_SAMSUNG_ONE_HAND
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.w3c.dom.Element

internal const val SAMSUNG_ONE_HAND_EXTENSION = "extensions/samsungonehand.mpe"

private const val EXT = "Lorg/ungoogled/samsungonehand"
private const val SERVICE = "org.ungoogled.samsungonehand.ActionService"

private val log = java.util.logging.Logger.getLogger("OneHandOperation")

/** Sends the app's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_ONE_HAND_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        log.info("One UI members: $calls calls, $fields fields")
        // Measured against 9.3.13.0: 68 calls, 30 field accesses.
        if (calls < 20) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * Declares the accessibility service the user turns on (extension ActionService): it performs gestures and Android's
 * global actions, and reads no window content. Do Not Disturb access lets the app's Do Not Disturb and sound mode
 * actions work.
 */
private val manifestPatch = resourcePatch(
    description = "Adds the accessibility service that performs the app's actions.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val application = manifest.getElementsByTagName("application").item(0) as Element
            root.insertBefore(manifest.createElement("uses-permission").apply {
                setAttribute("android:name", "android.permission.ACCESS_NOTIFICATION_POLICY")
            }, application)
            application.appendChild(manifest.createElement("service").apply {
                setAttribute("android:name", SERVICE)
                setAttribute("android:exported", "true")
                setAttribute("android:label", "@string/app_name")
                setAttribute("android:permission", "android.permission.BIND_ACCESSIBILITY_SERVICE")
                appendChild(manifest.createElement("intent-filter").apply {
                    appendChild(manifest.createElement("action").apply {
                        setAttribute("android:name", "android.accessibilityservice.AccessibilityService")
                    })
                })
                appendChild(manifest.createElement("meta-data").apply {
                    setAttribute("android:name", "android.accessibilityservice")
                    setAttribute("android:resource", "@xml/ungoogled_accessibility")
                })
            })
        }

        document("res/values/strings.xml").use { strings ->
            strings.documentElement.appendChild(strings.createElement("string").apply {
                setAttribute("name", "ungoogled_accessibility_description")
                textContent = "Lets One Hand Operation + go back, home, to recents and the notification panel, and " +
                    "play its taps and swipes. It reads no screen content, only which app is open."
            })
        }
        get("res/xml/ungoogled_accessibility.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
                    android:description="@string/ungoogled_accessibility_description"
                    android:accessibilityEventTypes="typeWindowStateChanged"
                    android:accessibilityFeedbackType="feedbackGeneric"
                    android:accessibilityFlags="flagDefault"
                    android:canPerformGestures="true"
                    android:canRetrieveWindowContent="false"
                    android:notificationTimeout="100"
                    android:settingsActivity="com.samsung.android.sidegesturepad.settings.SGPSettingsActivity" />
                """.trimIndent() + "\n",
            )
        }
    }
}

/**
 * One UI's look: its icons are SPR files (Samsung's vector format, which only One UI draws), and the app colours its
 * summaries and mini-action rows with the theme's colorPrimaryDark, One UI's blue there but a pale grey in Android's
 * own DeviceDefault theme (the rows were unreadable). The app's DeviceDefault themes get its own One UI blues.
 */
private val oneUiLookPatch = resourcePatch(
    description = "Converts Samsung's SPR icons and gives the app's themes One UI's accent colour.",
) {
    execute {
        val converted = convertSprDrawables()
        decode("res/values/styles.xml")
        var themed = 0
        document("res/values/styles.xml").use { styles ->
            val all = styles.documentElement.getElementsByTagName("style")
            for (i in 0 until all.length) {
                val style = all.item(i) as Element
                val parent = style.getAttribute("parent")
                if (!parent.startsWith("@android:style/Theme.DeviceDefault")) continue
                val items = style.getElementsByTagName("item")
                if ((0 until items.length).any { (items.item(it) as Element).getAttribute("name") == "android:colorPrimaryDark" }) continue
                style.appendChild(styles.createElement("item").apply {
                    setAttribute("name", "android:colorPrimaryDark")
                    textContent = if (parent.contains(".Light")) "@color/sesl_primary_dark_color_light" else "@color/sesl_primary_dark_color_dark"
                })
                themed++
            }
        }
        log.info("SPR drawables: $converted, themes: $themed")
        // Measured against 9.3.13.0: 12 SPR files, 5 themes.
        if (converted == 0 || themed == 0) throw PatchException("no SPR drawables or DeviceDefault themes found")
    }
}

/** Off One UI the app refuses to start ("unsupported OS"): it checks for a Samsung phone on an official build. */
private val supportedOsPatch = bytecodePatch(
    description = "Lets the app start off One UI.",
) {
    execute {
        SupportedOsFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}

/**
 * The app's actions (see extension Actions): before the app runs one itself, Actions runs it through the accessibility
 * service or Android's own calls; the app then only gives its touch feedback. Its key presses and touches (injected
 * input events, which only the system may send) go to Actions.key and Gestures.inject, and its action lists leave out
 * what needs One UI.
 */
private val actionsPatch = bytecodePatch(
    description = "Runs the app's actions through the accessibility service and Android's own calls.",
) {
    extendWith(SAMSUNG_ONE_HAND_EXTENSION)

    execute {
        // runAction(name, ...): right before its first own check ("shortcutid"), after it noted which handle asked.
        val run = RunActionFingerprint.method
        val code = run.implementation!!.instructions.toList()
        val shortcut = code.indexOfFirst {
            it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == "shortcutid"
        }
        if (shortcut < 0) throw PatchException("runAction no longer checks \"shortcutid\"")
        val scratch = (code[shortcut] as OneRegisterInstruction).registerA
        val name = code.drop(shortcut + 1).firstNotNullOfOrNull { insn ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            if (ref?.definingClass == "Ljava/lang/String;" && ref.name == "startsWith") (insn as FiveRegisterInstruction).registerC else null
        } ?: throw PatchException("runAction no longer tests the action name")
        val self = code.take(shortcut).lastOrNull { insn ->
            val field = (insn as? ReferenceInstruction)?.reference as? FieldReference
            insn.opcode in IPUT && field?.definingClass == run.definingClass
        }?.let { (it as TwoRegisterInstruction).registerB } ?: throw PatchException("runAction no longer notes its state")
        // Its touch feedback: the controller's no-argument method it calls most after an action.
        val feedback = code.mapNotNull { insn ->
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            ref?.takeIf {
                insn.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == run.definingClass && it.parameterTypes.isEmpty() &&
                    it.returnType == "V"
            }?.name
        }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: throw PatchException("runAction gives no touch feedback")
        // Both handles' paths meet at "shortcutid": the call goes onto that label, the branch after it.
        run.addInstructionsAtLabel(
            shortcut,
            """
                invoke-static/range { v$name .. v$name }, $EXT/Actions;->run(Ljava/lang/String;)Z
                move-result v$scratch
            """,
        )
        run.addInstructionsWithLabels(
            shortcut + 2,
            """
                if-eqz v$scratch, :stock
                invoke-virtual/range { v$self .. v$self }, ${run.definingClass}->$feedback()V
                return v$scratch
            """,
            ExternalLabel("stock", run.getInstruction(shortcut + 2)),
        )

        val supported = ActionSupportedFingerprint.method
        supported.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXT/Actions;->supported(Ljava/lang/String;)I
                move-result v0
                if-eqz v0, :stock
                if-gez v0, :offered
                const/4 v0, 0x0
                return v0
                :offered
                const/4 v0, 0x1
                return v0
            """,
            ExternalLabel("stock", supported.getInstruction(0)),
        )

        val key = InjectKeyFingerprint.method
        if (key.implementation!!.instructions.none { (it as? ReferenceInstruction)?.reference.let { r -> r is MethodReference && r.definingClass == "Landroid/view/KeyEvent;" && r.name == "<init>" } }) {
            throw PatchException("the key injector no longer makes a KeyEvent")
        }
        key.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p4 }, $EXT/Actions;->key(IIII)Z
                move-result v0
                if-eqz v0, :stock
                return-void
            """,
            ExternalLabel("stock", key.getInstruction(0)),
        )

        val motion = InjectMotionFingerprint.method
        motion.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p1 }, $EXT/Gestures;->inject(Landroid/content/Context;Landroid/view/MotionEvent;)Z
                move-result v0
                if-eqz v0, :stock
                return-void
            """,
            ExternalLabel("stock", motion.getInstruction(0)),
        )
    }
}

/**
 * The handles and panels are One UI system windows. Every window call goes through Windows, which adds those as
 * accessibility overlays through the service (no system permission needed) and passes every other window on. One UI's
 * status bar manager, which the app asks whether the notification panel is open, is answered by a stand-in, and the
 * settings writes only One UI allows it (gesture navigation, secure settings) are skipped where Android refuses them.
 */
private val windowsPatch = bytecodePatch(
    description = "Draws the app's handles and panels as accessibility overlays.",
) {
    extendWith(SAMSUNG_ONE_HAND_EXTENSION)

    execute {
        val add = "$EXT/Windows;->add(Landroid/view/ViewManager;Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V"
        val update = "$EXT/Windows;->update(Landroid/view/ViewManager;Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V"
        val remove = "$EXT/Windows;->remove(Landroid/view/ViewManager;Landroid/view/View;)V"
        val calls = mutableMapOf<String, String>()
        for (owner in listOf("Landroid/view/ViewManager;", "Landroid/view/WindowManager;")) {
            calls["$owner->addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V"] = add
            calls["$owner->updateViewLayout(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V"] = update
            calls["$owner->removeView(Landroid/view/View;)V"] = remove
        }
        calls["Landroid/view/WindowManager;->removeViewImmediate(Landroid/view/View;)V"] =
            "$EXT/Windows;->removeImmediate(Landroid/view/WindowManager;Landroid/view/View;)V"
        calls["Landroid/content/Context;->getSystemService(Ljava/lang/Class;)Ljava/lang/Object;"] =
            "$EXT/OneHand;->service(Landroid/content/Context;Ljava/lang/Class;)Ljava/lang/Object;"
        for ((table, helper) in listOf("Global" to "putGlobal", "Secure" to "putSecure", "System" to "putSystem")) {
            calls["Landroid/provider/Settings\$$table;->putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z"] =
                "$EXT/OneHand;->$helper(Landroid/content/ContentResolver;Ljava/lang/String;I)Z"
        }
        calls["Landroid/view/Window;->setAttributes(Landroid/view/WindowManager\$LayoutParams;)V"] =
            "$EXT/Windows;->attributes(Landroid/view/Window;Landroid/view/WindowManager\$LayoutParams;)V"
        val moved = redirectCalls(calls)
        log.info("Window, service and settings calls: $moved")

        // Dialogs made outside an activity (the floating navigation buttons): their Context gives the accessibility
        // service's WindowManager.
        var dialogs = 0
        val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/") || c.type.startsWith("Landroidx/") || c.type.startsWith("Lcom/google/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any { it.isDialogInit() } == true) sites += c.type to m
            }
        }
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val code = mutable.implementation!!.instructions.toList()
            for (index in code.indices.reversed()) {
                if (!code[index].isDialogInit()) continue
                val context = (code[index] as FiveRegisterInstruction).registerD
                mutable.addInstructionsAtLabel(
                    index,
                    """
                        invoke-static/range { v$context .. v$context }, $EXT/Windows;->dialogContext(Landroid/content/Context;)Landroid/content/Context;
                        move-result-object v$context
                    """,
                )
                dialogs++
            }
        }
        log.info("Window dialogs: $dialogs")
        // Measured against 9.3.13.0: 69 window calls, 16 service lookups, 14 settings writes, 4 window attributes.
        if (moved < 40) throw PatchException("only $moved window calls found")
    }
}

/**
 * The handle windows are as tall as the screen and say which part takes touches through hidden window insets, which
 * Android refuses an app (the app crashed reading them). The app's listener is kept for extension Insets, which sets
 * the same region through Android's public touchable region; the hidden field and call go there too.
 */
private val touchableRegionPatch = bytecodePatch(
    description = "Lets only the handles take touches, through Android's public touchable region.",
) {
    extendWith(SAMSUNG_ONE_HAND_EXTENSION)

    execute {
        val info = "Landroid/view/ViewTreeObserver\$InternalInsetsInfo;"
        var fields = 0
        var registrations = 0
        val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/") || c.type.startsWith("Landroid/")) return@classDefForEach
            for (m in c.methods) {
                val code = m.implementation?.instructions ?: continue
                if (code.any { insn ->
                        val ref = (insn as? ReferenceInstruction)?.reference
                        (ref is FieldReference && ref.definingClass == info && ref.name == "touchableRegion") ||
                            (ref is StringReference && ref.string.endsWith("OnComputeInternalInsetsListener"))
                    }) sites += c.type to m
            }
        }
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            registrations += mutable.redirectReflectiveCalls("OnComputeInternalInsetsListener", "$EXT/Insets;->register")
            // The hidden field: from the end, so earlier indexes stay valid.
            val code = mutable.implementation!!.instructions.toList()
            for (index in code.indices.reversed()) {
                val insn = code[index]
                val ref = (insn as? ReferenceInstruction)?.reference
                if (insn.opcode == Opcode.IGET_OBJECT && ref is FieldReference && ref.definingClass == info && ref.name == "touchableRegion") {
                    val two = insn as TwoRegisterInstruction
                    mutable.replaceInstruction(index, "invoke-static/range { v${two.registerB} .. v${two.registerB} }, $EXT/Insets;->region(Ljava/lang/Object;)Landroid/graphics/Region;")
                    mutable.addInstruction(index + 1, "move-result-object v${two.registerA}")
                    fields++
                }
            }
        }
        val calls = redirectCalls(
            mapOf("$info->setTouchableInsets(I)V" to "$EXT/Insets;->touchable(Ljava/lang/Object;I)V"),
        )
        log.info("Touchable region: $fields field reads, $calls calls, $registrations registrations")
        // Measured against 9.3.13.0: 3 listeners, registered in 3 calls.
        if (fields == 0 || registrations == 0) throw PatchException("the app no longer computes a touchable region")
    }
}

/**
 * The app learns which app is in front from a task stack listener and getRunningTasks, which only the system may use.
 * Its listener goes to extension Tasks, which the accessibility service's window events drive, and getRunningTasks
 * answers with the activity in front (handles hidden in chosen apps or on the home screen).
 */
private val foregroundAppPatch = bytecodePatch(
    description = "Tells the app which app is in front, from the accessibility service's window events.",
) {
    extendWith(SAMSUNG_ONE_HAND_EXTENSION)

    execute {
        var registrations = 0
        val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any { insn ->
                        ((insn as? ReferenceInstruction)?.reference as? StringReference)?.string?.endsWith("egisterTaskStackListener") == true
                    } == true) sites += c.type to m
            }
        }
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            registrations += mutable.redirectReflectiveCalls("egisterTaskStackListener", "$EXT/Tasks;->register")
        }
        // The listener's calls up to Android's (empty) TaskStackListener methods are refused an app: dropped.
        var supers = 0
        classDefForEach { c ->
            if (c.superclass != "Landroid/app/TaskStackListener;") return@classDefForEach
            val mutable = mutableClassDefBy(c.type)
            for (m in mutable.methods) {
                val code = m.implementation?.instructions?.toList() ?: continue
                for ((index, insn) in code.withIndex()) {
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (insn.opcode == Opcode.INVOKE_SUPER && ref.definingClass == "Landroid/app/TaskStackListener;" &&
                        ref.name != "<init>"
                    ) {
                        m.replaceInstruction(index, "nop")
                        supers++
                    }
                }
            }
        }
        val calls = redirectCalls(
            mapOf(
                "Landroid/app/ActivityManager;->getRunningTasks(I)Ljava/util/List;" to
                    "$EXT/Tasks;->running(Landroid/app/ActivityManager;I)Ljava/util/List;",
            ),
        )
        log.info("Foreground app: $registrations listener registrations, $supers listener super calls, $calls task lookups")
        // Measured against 9.3.13.0: 2 registrations (register, unregister), 20 lookups.
        if (registrations == 0 || calls == 0) throw PatchException("the app no longer watches the app in front")
    }
}

/**
 * The quick tools panel: its tile and slider colours are One UI SystemUI colours looked up by name (Android's SystemUI
 * has none, so the tiles were white on white), and its brightness slider uses hidden DisplayManager calls by
 * reflection. The colours fall back to the app's own; the brightness goes through the brightness setting (extension
 * Brightness).
 */
private val quickToolsPatch = bytecodePatch(
    description = "Gives the quick tools their colours and a working brightness slider.",
) {
    extendWith(SAMSUNG_ONE_HAND_EXTENSION)

    execute {
        SystemUiColorFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p1 }, $EXT/OneHand;->systemUiColor(Landroid/content/Context;Ljava/lang/String;)I
                move-result v0
                return v0
            """,
        )
        var calls = 0
        val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any { insn ->
                        ((insn as? ReferenceInstruction)?.reference as? StringReference)?.string?.let {
                            it == "getBrightness" || it == "setBrightness" || it == "setTemporaryBrightness"
                        } == true
                    } == true) sites += c.type to m
            }
        }
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            calls += mutable.redirectReflectiveCalls("Brightness", "$EXT/Brightness;->call")
        }
        log.info("Quick tools: $calls brightness calls")
        // Measured against 9.3.13.0: 6 (get, set, set temporary; One UI 8 and older).
        if (calls < 3) throw PatchException("only $calls brightness calls found")
    }
}

/** Asks for the accessibility service from the main screen while the app's switch is on (extension Access). */
private val accessPatch = bytecodePatch(
    description = "Asks for the accessibility service from the app's main screen.",
) {
    extendWith(SAMSUNG_ONE_HAND_EXTENSION)

    execute {
        SettingsResumeFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, $EXT/Access;->resumed(Landroid/app/Activity;)V",
        )
    }
}

/**
 * Off One UI the app refuses to start, and its gestures press keys, touch the screen and add system windows, which
 * only One UI's system lets it do. The extension carries stand-ins for the One UI classes it calls and an
 * accessibility service that performs its actions instead.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Runs One Hand Operation + on non-One UI ROMs through an accessibility service.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_ONE_HAND)
    dependsOn(oneUiMembersPatch, manifestPatch, oneUiLookPatch, supportedOsPatch, actionsPatch, windowsPatch, touchableRegionPatch, foregroundAppPatch, quickToolsPatch, accessPatch)
    extendWith(SAMSUNG_ONE_HAND_EXTENSION)
}
