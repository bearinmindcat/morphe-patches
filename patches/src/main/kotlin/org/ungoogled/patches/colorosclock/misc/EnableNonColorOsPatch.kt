package org.ungoogled.patches.colorosclock.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Element
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.coloros.treatRefusedComponentsAsMissing
import org.ungoogled.patches.colorosclock.ColorOsClockConstants.COMPATIBILITY_COLOROS_CLOCK
import org.ungoogled.patches.coloros.privateFrameworkClassesPatch

internal const val COLOROS_CLOCK_EXTENSION = "extensions/colorosclock.mpe"

private const val RUNTIME = "Lorg/ungoogled/colorosclock/ColorOS;"
private const val CONSENT = "Lorg/ungoogled/colorosclock/Consent;"
private const val ALERTS = "Lorg/ungoogled/colorosclock/Alerts;"
private const val RINGTONES = "Lorg/ungoogled/colorosclock/Ringtones;"
private const val WIDGETS = "Lorg/ungoogled/colorosclock/Widgets;"

private val log = java.util.logging.Logger.getLogger("OnePlusClock")

/** DeviceUtils.isQcomPlatform(Context), by its log text. */
private object IsQcomPlatformFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("isQcomPlatform hardware : "),
)

/** DeviceUtils.isMtkPlatform(Context), by its log text. */
private object IsMtkPlatformFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("isMtkPlatform hardware : "),
)

/** BaseCloudConfig.canAccessCloudConfig(), by its log text. */
private object CanAccessCloudConfigFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("User not agree permission, skip cloud config"),
)

/** AlarmNotificationUtils.getAlarmRingingNotification(Context, AlarmSchedule, String), by its log text. */
private object AlarmRingingNotificationFingerprint : Fingerprint(
    returnType = "Lkotlin/Pair;",
    strings = listOf("getAlarmRingingNotification"),
)

/** NotificationUtils' placeholder foreground notification for the alarm service, by its log text. */
private object AlarmPlaceholderForegroundFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/app/Service;", "I"),
    strings = listOf("startForegroundService with Default Notification"),
)

/** AlarmRingUtils' sound picker intent (Context, Uri, int, boolean, int), by ColorOS's picker action. */
private object RingtonePickerFingerprint : Fingerprint(
    returnType = "Landroid/content/Intent;",
    strings = listOf("oppo.intent.action.ACTION_MUSIC_SELECT"),
)

private const val OPLUS_TEXT_CLOCK = "com.oplus.widget.OplusTextClock"

/** DeviceUtils' "OnePlus red hour" check (does com.oplus.widget.OplusTextClock load?), by the class it looks for. */
private object RedHourWidgetFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    strings = listOf(OPLUS_TEXT_CLOCK),
)

private const val APP_WIDGET_MANAGER = "Landroid/appwidget/AppWidgetManager;"
private val WIDGET_UPDATES = setOf("updateAppWidget", "partiallyUpdateAppWidget")

/**
 * Home screen widgets are inflated by the launcher, which only has the framework's views. The OnePlus widget layouts
 * with a red hour use ColorOS's OplusTextClock; those with a TextClock twin are left for the code to choose (see
 * RedHourWidgetFingerprint), the rest (the OxygenOS widget has no twin) get Android's TextClock.
 */
private val widgetLayoutsPatch = resourcePatch {
    execute {
        // Patcher 2.0 decodes resources on request: decode the widget layouts read below.
        decode("res/layout*/*widget*.xml")
        val layouts = get("res").listFiles { file -> file.isDirectory && file.name.startsWith("layout") }.orEmpty()
            .flatMap { dir -> dir.listFiles { file -> file.name.endsWith(".xml") }.orEmpty().toList() }
            .filter { OPLUS_TEXT_CLOCK in it.readText() }
        if (layouts.isEmpty()) throw PatchException("no widget layout uses $OPLUS_TEXT_CLOCK")
        var rewritten = 0
        layouts.forEach { file ->
            if (!file.name.contains("_red_widget")) throw PatchException("$OPLUS_TEXT_CLOCK outside a red widget layout: ${file.name}")
            if (java.io.File(file.parentFile, file.name.replace("_red_widget", "_widget")).isFile) return@forEach
            file.writeText(
                file.readText().replace("<$OPLUS_TEXT_CLOCK ", "<TextClock ").replace("</$OPLUS_TEXT_CLOCK>", "</TextClock>"),
            )
            rewritten++
        }
        // Measured against 16.19.8: 24 layouts, 3 without a twin (the OxygenOS clock and weather widget).
        log.info("widget layouts with $OPLUS_TEXT_CLOCK: ${layouts.size}, rewritten to TextClock: $rewritten")
        if (rewritten == 0) throw PatchException("expected red widget layouts without a TextClock twin")
    }
}

private const val ALARM_SERVICE = "com.oplus.alarmclock.alert.AlarmService"

/**
 * The alarm and timer screens open from the background through full-screen notifications (see Alerts). The ringing
 * alarm's service may also run as systemExempted, Android's foreground service type for alarm clocks (see
 * Alerts.alarmForeground).
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val list = manifest.getElementsByTagName("uses-permission")
            listOf("android.permission.USE_FULL_SCREEN_INTENT", "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED")
                .forEach { permission ->
                    val declared = (0 until list.length).any { (list.item(it) as Element).getAttribute("android:name") == permission }
                    if (!declared) {
                        manifest.documentElement.insertBefore(
                            manifest.createElement("uses-permission").apply { setAttribute("android:name", permission) },
                            manifest.getElementsByTagName("application").item(0),
                        )
                    }
                }
            val services = manifest.getElementsByTagName("service")
            val alarmService = (0 until services.length).map { services.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == ALARM_SERVICE }
                ?: throw PatchException("$ALARM_SERVICE not declared in the manifest")
            val types = alarmService.getAttribute("android:foregroundServiceType").split("|").filter { it.isNotBlank() }
            if ("mediaPlayback" !in types) throw PatchException("$ALARM_SERVICE is not a mediaPlayback service: $types")
            if ("systemExempted" !in types) {
                alarmService.setAttribute("android:foregroundServiceType", (types + "systemExempted").joinToString("|"))
            }
        }
    }
}

/**
 * On OPPO / OnePlus / realme phones and custom ROMs made for them (their oplus-fwk.jar), the phone's own ColorOS
 * classes load before the extension's stand-ins and refuse an installed app (issue #155): the app uses private copies
 * of its stand-ins instead (see privatizeFrameworkClasses). OplusTextClock stays: its widget layouts name it.
 */
// Measured against 16.19.8: 290 classes.
private val privateClassesPatch = privateFrameworkClassesPatch(
    "OnePlusClock", COLOROS_CLOCK_EXTENSION, minimum = 250, keep = setOf("Lcom/oplus/widget/OplusTextClock;"),
)

/**
 * Off ColorOS Clock dies at once on com.oplus.content.OplusFeatureConfigManager (a ColorOS framework class). The
 * extension carries stand-ins for the ColorOS classes it uses (generated by .github/scripts/coloros-standins); on an
 * OPPO / OnePlus / realme phone the framework's own classes load first, so they never run there. Its broadcast
 * receivers registered at run time go through exportDynamicReceivers.
 *
 * Alarms: Clock sets the alarm that rings only on Qualcomm hardware (Build.HARDWARE "qcom": a plain RTC_WAKEUP alarm)
 * or MediaTek ("mt...": ColorOS's power-off alarm types); on any other phone it set only the status-bar indicator and
 * nothing rang. Every phone now takes the Qualcomm path. ColorOS opens the alarm and timer screens straight from the
 * background, which Android refuses a re-signed copy: they come up through full-screen notifications instead (Alerts).
 * Its ringing service runs as systemExempted, not mediaPlayback, so an alarm right after a reboot's first unlock rings
 * on Android 15+ (Alerts.alarmForeground).
 * Sounds are picked with ColorOS Settings' picker: Android's where it is missing (Ringtones).
 *
 * Widgets: the launcher draws them with the framework's views only, so the OnePlus red-hour layouts (ColorOS's
 * OplusTextClock, found in the stand-ins) gave "Can't load widget"; they use TextClock now (Widgets), and leave out
 * the weather where ColorOS's weather service is missing.
 *
 * Network: its export build fetches cloud settings from OPPO's servers (HeyTap cloud config) at first launch without
 * asking; that now waits for an explicit Allow (Consent, asked once on the home screen).
 */
@Suppress("unused")
val enableNonColorOsPatch = bytecodePatch(
    name = "Enable non-ColorOS ROM support",
    description = "Makes OnePlus Clock installable and usable on non-ColorOS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_CLOCK)
    dependsOn(privateClassesPatch, manifestPatch, widgetLayoutsPatch)
    extendWith(COLOROS_CLOCK_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers(RUNTIME)
        log.info("registerReceiver calls through ColorOS: $receivers")
        // Measured against 16.19.8: 50 calls.
        if (receivers < 40) throw PatchException("only $receivers registerReceiver calls found")

        IsQcomPlatformFingerprint.method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        IsMtkPlatformFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        // Widgets: the red-hour layouts only where the launcher can inflate OplusTextClock (Widgets).
        RedHourWidgetFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "$OPLUS_TEXT_CLOCK"
                invoke-static {v0}, $WIDGETS->frameworkHas(Ljava/lang/String;)Z
                move-result v0
                return v0
            """,
        )

        // Their weather comes from ColorOS's weather service: where it is missing every widget update leaves the
        // weather views out (Widgets) instead of showing "--" that never fills.
        var widgetUpdates = 0
        val updateSites = mutableListOf<Pair<String, Method>>()
        fun Any.widgetUpdate(): MethodReference? {
            if (this !is ReferenceInstruction || opcode != Opcode.INVOKE_VIRTUAL) return null
            val r = reference as? MethodReference ?: return null
            return r.takeIf {
                it.definingClass == APP_WIDGET_MANAGER && it.name in WIDGET_UPDATES &&
                    it.parameterTypes.size == 2 && it.parameterTypes[0].toString() in setOf("I", "[I") &&
                    it.parameterTypes[1].toString() == "Landroid/widget/RemoteViews;"
            }
        }
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            c.methods.forEach { m ->
                if (m.implementation?.instructions?.any { it.widgetUpdate() != null } == true) updateSites += c.type to m
            }
        }
        updateSites.forEach { (type, method) ->
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                val r = insn.widgetUpdate() ?: continue
                val call = insn as FiveRegisterInstruction
                mutable.replaceInstruction(
                    index,
                    "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, " +
                        "$WIDGETS->${r.name}($APP_WIDGET_MANAGER${r.parameterTypes.joinToString("")})V",
                )
                widgetUpdates++
            }
        }
        // Measured against 16.19.8: 4 (WidgetUpdateHelper 3, DigitalAppWidgetProvider 1).
        log.info("widget updates through Widgets: $widgetUpdates")
        if (widgetUpdates < 2) throw PatchException("only $widgetUpdates widget updates found")

        CanAccessCloudConfigFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $CONSENT->network()Z
                move-result v0
                return v0
            """,
        )

        val home = mutableClassDefBy("Lcom/oplus/alarmclock/AlarmClock;")
        val resume = home.methods.firstOrNull { it.name == "onResume" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("AlarmClock.onResume not found")
        val superResume = resume.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_SUPER &&
                ((it as ReferenceInstruction).reference as MethodReference).name == "onResume"
        }
        if (superResume < 0) throw PatchException("super.onResume() not found in AlarmClock.onResume")
        resume.addInstructions(superResume + 1, "invoke-static {p0}, $CONSENT->ask(Landroid/app/Activity;)V")

        // The alarm's ringing notification carries the alarm screen as a full-screen intent. Right after its content
        // intent is set: later the method reuses the Context and schedule registers.
        AlarmRingingNotificationFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val created = instructions.indexOfFirst {
                it.opcode == Opcode.NEW_INSTANCE &&
                    ((it as ReferenceInstruction).reference as TypeReference).type == "Landroid/app/Notification\$Builder;"
            }
            val content = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((it as ReferenceInstruction).reference as MethodReference).name == "setContentIntent"
            }
            if (created < 0 || content < created) throw PatchException("alarm ringing notification builder not found")
            val builder = (instructions[created] as OneRegisterInstruction).registerA
            // Measured against 16.19.8: p0 (Context) and p1 (AlarmSchedule) are still untouched there.
            val params = implementation!!.registerCount - 3 // static (Context, AlarmSchedule, String): p0, p1
            val written = instructions.subList(0, content).any { insn ->
                insn.opcode.setsRegister() && (insn as? OneRegisterInstruction)?.registerA in setOf(params, params + 1)
            }
            if (written) throw PatchException("the alarm ringing notification reuses its parameters before the content intent")
            addInstructions(
                content + 1,
                "invoke-static {v$builder, p0, p1}, $ALERTS->alarmRinging(Landroid/app/Notification\$Builder;" +
                    "Landroid/content/Context;Landroid/os/Parcelable;)V",
            )
        }

        // The ringing alarm's service goes foreground as systemExempted where Android allows it (Alerts): as
        // mediaPlayback, Android 15+ refused it within the BOOT_COMPLETED allowance and the alarm crashed.
        val alarmService = mutableClassDefBy("L${ALARM_SERVICE.replace('.', '/')};")
        var ringingStarts = 0
        alarmService.methods.forEach { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@forEach
            instructions.forEachIndexed { index, insn ->
                val r = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
                if (insn.opcode != Opcode.INVOKE_VIRTUAL || r.name != "startForeground" ||
                    r.parameterTypes.joinToString("") != "ILandroid/app/Notification;"
                ) return@forEachIndexed
                val call = insn as FiveRegisterInstruction
                method.replaceInstruction(
                    index,
                    "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, " +
                        "$ALERTS->alarmForeground(Landroid/app/Service;ILandroid/app/Notification;)V",
                )
                ringingStarts++
            }
        }
        // Measured against 16.19.8: 1 (AlarmService.H, the ringing notification).
        if (ringingStarts != 1) throw PatchException("expected 1 startForeground in $ALARM_SERVICE, found $ringingStarts")
        AlarmPlaceholderForegroundFingerprint.method.apply {
            val compat = implementation!!.instructions.withIndex().filter { (_, insn) ->
                val r = (insn as? ReferenceInstruction)?.reference as? MethodReference
                insn.opcode == Opcode.INVOKE_STATIC && r?.definingClass == "Landroidx/core/app/ServiceCompat;" &&
                    r.name == "startForeground"
            }
            if (compat.size != 1) throw PatchException("expected 1 ServiceCompat.startForeground in the alarm placeholder, found ${compat.size}")
            val (index, insn) = compat.single()
            val call = insn as FiveRegisterInstruction
            replaceInstruction(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE}, v${call.registerF} }, " +
                    "$ALERTS->alarmForeground(Landroid/app/Service;ILandroid/app/Notification;I)V",
            )
        }

        // The timer screen: startActivity from its ringing service -> Alerts (adds a full-screen notification); its
        // lock check decides between that screen and a pop-up over the app in use, which a re-signed copy can't show.
        val timerScreen = "Lcom/oplus/alarmclock/timer/TimerAlertFullScreen;"
        var timerStarts = 0
        var keyguardChecks = 0
        mutableClassDefBy("Lcom/oplus/alarmclock/timer/TimerFloatingViewService;").methods.forEach { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@forEach
            val namesScreen = instructions.any { (it as? ReferenceInstruction)?.let { r -> (r.reference as? TypeReference)?.type } == timerScreen }
            if (!namesScreen) return@forEach
            instructions.forEachIndexed { index, insn ->
                val r = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEachIndexed
                if (insn.opcode == Opcode.INVOKE_VIRTUAL && r.definingClass == "Landroid/app/KeyguardManager;" && r.name == "isKeyguardLocked") {
                    method.replaceInstruction(
                        index,
                        "invoke-static { v${(insn as FiveRegisterInstruction).registerC} }, " +
                            "$ALERTS->timerKeyguardLocked(Landroid/app/KeyguardManager;)Z",
                    )
                    keyguardChecks++
                    return@forEachIndexed
                }
                if (insn.opcode != Opcode.INVOKE_VIRTUAL || r.definingClass != "Landroid/content/Context;" || r.name != "startActivity") return@forEachIndexed
                val call = insn as FiveRegisterInstruction
                val registers = listOf(call.registerC, call.registerD, call.registerE).take(call.registerCount)
                method.replaceInstruction(
                    index,
                    registers.joinToString(", ", "invoke-static { ", " }, ") { "v$it" } +
                        "$ALERTS->startTimerScreen(Landroid/content/Context;${r.parameterTypes.joinToString("")})V",
                )
                timerStarts++
            }
        }
        // Measured against 16.19.8: 2 (with and without launch options).
        if (timerStarts != 2) throw PatchException("expected 2 timer screen starts, found $timerStarts")
        if (keyguardChecks != 1) throw PatchException("expected 1 lock check before the timer screen, found $keyguardChecks")

        // ColorOS Settings' sound picker -> Android's where it is missing.
        RingtonePickerFingerprint.method.apply {
            val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
            if (returns.isEmpty()) throw PatchException("sound picker intent: no return")
            returns.reversed().forEach { (index, insn) ->
                val register = (insn as OneRegisterInstruction).registerA
                addInstructions(
                    index,
                    "invoke-static {v$register}, $RINGTONES->picker(Landroid/content/Intent;)Landroid/content/Intent;\n" +
                        "move-result-object v$register",
                )
            }
        }

        val klaxon = mutableClassDefBy("Lcom/oplus/alarmclock/timer/TimerKlaxon;").methods
            .firstOrNull { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("TimerKlaxon.onDestroy not found")
        klaxon.addInstructions(0, "invoke-static {p0}, $ALERTS->timerStopped(Landroid/content/Context;)V")

        // On OPPO, OnePlus and realme phones ColorOS's own apps are there but refuse it: a refusal reads as their absence.
        val (services, providers) = treatRefusedComponentsAsMissing(RUNTIME)
        log.info("Service calls: $services, provider calls: $providers through ColorOS")
    }
}
