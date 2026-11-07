package org.ungoogled.patches.motolauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.coloroslauncher.misc.rebaseSubclasses
import org.ungoogled.patches.coloroslauncher.misc.redirectConstructors
import org.ungoogled.patches.coloroslauncher.misc.redirectHiddenMembers
import org.ungoogled.patches.coloroslauncher.misc.skipCastOfNull
import org.ungoogled.patches.coloroslauncher.misc.substituteSuperConstructors
import org.ungoogled.patches.motolauncher.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.motolauncher.HIDDEN_CONSTRUCTORS_16_17
import org.ungoogled.patches.motolauncher.HIDDEN_CONSTRUCTORS_CANARY
import org.ungoogled.patches.motolauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.motolauncher.HIDDEN_MEMBERS_16_17
import org.ungoogled.patches.motolauncher.HIDDEN_MEMBERS_CANARY
import org.ungoogled.patches.motolauncher.HIDDEN_SUPERCLASSES
import org.ungoogled.patches.motolauncher.MotoLauncherConstants.COMPATIBILITY_MOTO_LAUNCHER
import org.ungoogled.patches.motolauncher.SUPER_CONSTRUCTORS
import org.ungoogled.patches.motolauncher.v13.HIDDEN_CONSTRUCTORS as V13_HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.motolauncher.v13.HIDDEN_MEMBERS as V13_HIDDEN_MEMBERS
import org.ungoogled.patches.motolauncher.v13.HIDDEN_SUPERCLASSES as V13_HIDDEN_SUPERCLASSES
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.shared.supportedVersionCheck

internal const val MOTO_LAUNCHER_EXTENSION = "extensions/motolauncher.mpe"

private const val EXT = "Lorg/ungoogled/motolauncher"

private val log = java.util.logging.Logger.getLogger("MotoLauncher")

/**
 * Version 13 is the Android 13 build: its lists are generated against Android 13, 15, 16, 17 and the canary
 * (v13/MotoLauncher13Members.kt), and it is the older Launcher3 (no background widget host, no pending-intent helper).
 */
private fun isAndroid13Build(version: String?) = version == "13"

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
    // The per-user calls (hidden or system-only where not public): for the app's own user.
    "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I" to "GetIntForUser",
    "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)I" to "GetIntForUser",
    "getLongForUser(Landroid/content/ContentResolver;Ljava/lang/String;JI)J" to "GetLongForUser",
    "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;" to "GetStringForUser",
    "putIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)Z" to "PutIntForUser",
    "putStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;I)Z" to "PutStringForUser",
)

/** Every settings call goes through LocalSettings, which keeps a refused write in the app's own preferences. */
private val SETTINGS_CALLS: Map<String, String> = buildMap {
    for (table in listOf("Global", "Secure", "System")) {
        for ((shape, suffix) in SETTINGS_SHAPES) {
            put(
                "Landroid/provider/Settings\$$table;->$shape",
                "$EXT/LocalSettings;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}",
            )
        }
    }
}

/** Window contexts and windows of types only the system may use (see Windows). */
private val WINDOW_CALLS = listOf(
    "Landroid/content/Context;" to "createWindowContext(ILandroid/os/Bundle;)Landroid/content/Context;",
    "Landroid/content/Context;" to "createWindowContext(Landroid/view/Display;ILandroid/os/Bundle;)Landroid/content/Context;",
    "Landroid/view/ViewManager;" to "addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
    "Landroid/view/ViewManager;" to "updateViewLayout(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
    "Landroid/view/ViewManager;" to "removeView(Landroid/view/View;)V",
    "Landroid/view/WindowManager;" to "removeViewImmediate(Landroid/view/View;)V",
    "Landroid/content/Context;" to "getDisplay()Landroid/view/Display;",
    "Landroid/window/OnBackInvokedDispatcher;" to "registerOnBackInvokedCallback(ILandroid/window/OnBackInvokedCallback;)V",
    "Landroid/content/ContextWrapper;" to "getDisplay()Landroid/view/Display;",
).associate { (owner, shape) ->
    "$owner->$shape" to "$EXT/Windows;->${shape.substringBefore('(')}($owner${shape.substringAfter('(')}"
}

/** Reflection on Android's hidden parts (see Reflect). */
private val REFLECTION_CALLS = listOf(
    "getField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
    "getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
    "getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
    "getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
).associate { shape ->
    "Ljava/lang/Class;->$shape" to "$EXT/Reflect;->${shape.substringBefore('(')}(Ljava/lang/Class;${shape.substringAfter('(')}"
}

/** Calls an installed app may make but that fail for it (see Calls). */
private val GUARDED_CALLS = mapOf(
    "Landroid/content/ContentResolver;->registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;)V" to
        "$EXT/Calls;->registerContentObserver(Landroid/content/ContentResolver;Landroid/net/Uri;ZLandroid/database/ContentObserver;)V",
    "Landroid/app/ActivityManager;->getCurrentUser()I" to "$EXT/Calls;->getCurrentUser()I",
)

/** DeviceConfig reads (Launcher3's device flags): Android 13 refuses them to an installed app (see Calls). */
private val DEVICE_CONFIG_CALLS = mapOf(
    "Landroid/provider/DeviceConfig;->getBoolean(Ljava/lang/String;Ljava/lang/String;Z)Z" to
        "$EXT/Calls;->getBoolean(Ljava/lang/String;Ljava/lang/String;Z)Z",
)

/**
 * Moto App Launcher is a system app built against the full Android framework; an installed app may not link to its hidden
 * parts. Each hidden member it uses goes through Members (used where Android allows it, "absent" elsewhere), each
 * `new` of a class an app may not construct through Members.construct, and subclasses of such classes are rebased.
 * The lists are generated (.github/scripts/coloros-launcher/hidden.py), the helpers are the OnePlus System Launcher
 * port's (the same Launcher3 / Quickstep base).
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(MOTO_LAUNCHER_EXTENSION)

    execute {
        val android13Build = isAndroid13Build(packageMetadata.versionName)
        // APKMirror's release "13" has two variants with the same version: the October 2023 one adds the Sentry crash
        // reporter and a newer launcher build this patch was not made for.
        if (android13Build && classDefByOrNull("Lio/sentry/Sentry;") != null) {
            throw PatchException(
                "This is the second Moto App Launcher 13 variant (October 2023); patch the first one " +
                    "(\"moto-app-launcher-13\", April 2023, see docs/APK-SOURCES.md in the patches repository).",
            )
        }
        val (calls, fields) = redirectHiddenMembers(
            "$EXT/Members;", "$EXT/Hidden;",
            if (android13Build) V13_HIDDEN_MEMBERS else HIDDEN_MEMBERS + HIDDEN_MEMBERS_16_17 + HIDDEN_MEMBERS_CANARY,
        )
        val constructors = redirectConstructors(
            "$EXT/Members;", "$EXT/Hidden;",
            if (android13Build) V13_HIDDEN_CONSTRUCTORS else HIDDEN_CONSTRUCTORS + HIDDEN_CONSTRUCTORS_16_17 + HIDDEN_CONSTRUCTORS_CANARY,
        )
        val superCalls = if (android13Build) 0 else substituteSuperConstructors(SUPER_CONSTRUCTORS)
        val superclasses = if (android13Build) V13_HIDDEN_SUPERCLASSES else HIDDEN_SUPERCLASSES
        val rebased = rebaseSubclasses(superclasses)
        // The launcher's own PendingIntentCompat.newPendingIntent takes an IIntentSender.Stub, a rebased class: its two
        // callers (TouchInteractionService's search and all-apps actions) pass rebased senders, which would no longer
        // verify. They call Launch.pendingIntent instead, which makes the same pending intent. (13 has no such helper.)
        var pendingIntents = 0
        if (!android13Build) {
            val pendingIntentShape = "(Landroid/content/IIntentSender\$Stub;)Landroid/app/PendingIntent;"
            val pendingIntentCompat = buildMap {
                classDefForEach { c ->
                    if (c.methods.any { m -> m.name == "newPendingIntent" && "(${m.parameterTypes.joinToString("")})${m.returnType}" == pendingIntentShape }) {
                        put("${c.type}->newPendingIntent$pendingIntentShape", "$EXT/Launch;->pendingIntent(Ljava/lang/Object;)Landroid/app/PendingIntent;")
                    }
                }
            }
            if (pendingIntentCompat.size != 1) throw PatchException("expected one PendingIntentCompat.newPendingIntent, found ${pendingIntentCompat.keys}")
            pendingIntents = redirectCalls(pendingIntentCompat)
            if (pendingIntents == 0) throw PatchException("no PendingIntentCompat.newPendingIntent calls found")
        }
        val casts = nullCastsToRebasedBases(superclasses.keys)
        skipCastOfNull("$EXT/Hidden;")
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; super calls: $superCalls; rebased classes: $rebased; casts to their old bases: $casts; pending intents: $pendingIntents")
        // Measured against 13: 815 calls, 128 field accesses, 39 constructors, 9 rebased classes, 15 casts.
        if (calls < 100) throw PatchException("only $calls hidden member calls found")
        if (constructors == 0) throw PatchException("hidden constructors not found")
        if (rebased == 0) throw PatchException("no subclasses of hidden Android classes found")
    }
}

/** The methods of the app (not our extensions) that cast to one of [types]. */
private fun BytecodePatchContext.castSites(types: Set<String>): List<Pair<String, Method>> {
    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any {
                    it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type in types
                } == true
            ) sites += c.type to m
        }
    }
    return sites
}

private fun BytecodePatchContext.mutableMethod(type: String, method: Method) = mutableClassDefBy(type).methods.first {
    it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
}

/**
 * A rebased object (see rebaseSubclasses) is no longer of its old Android base class, so a cast to that class throws.
 * rebaseSubclasses leaves fields of that type empty; the cast now gives null too. Returns the casts changed.
 */
private fun BytecodePatchContext.nullCastsToRebasedBases(bases: Set<String>): Int {
    var changed = 0
    for ((type, method) in castSites(bases)) {
        val m = mutableMethod(type, method)
        val instructions = m.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            if (insn.opcode != Opcode.CHECK_CAST || ((insn as ReferenceInstruction).reference as TypeReference).type !in bases) continue
            val r = (insn as OneRegisterInstruction).registerA
            m.replaceInstruction(index, if (r < 16) "const/4 v$r, 0x0" else "const/16 v$r, 0x0")
            changed++
        }
    }
    return changed
}

/**
 * What a system home screen may do and an installed app may not: keep options in Android's settings tables, register
 * receivers without saying who may send, open system-only windows, animate launches remotely, ask the app prediction
 * service, get widget updates through a system-only listener.
 */
private val systemPrivilegesPatch = bytecodePatch(
    description = "Keeps its options locally and uses Android's own launch animations and widget updates.",
) {
    extendWith(MOTO_LAUNCHER_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers("$EXT/Receivers;")
        val settings = redirectCalls(SETTINGS_CALLS)
        val windows = redirectCalls(WINDOW_CALLS)
        val reflection = redirectCalls(REFLECTION_CALLS)
        val guarded = redirectCalls(GUARDED_CALLS)
        val android13Build = isAndroid13Build(packageMetadata.versionName)
        val deviceConfig = if (android13Build) redirectCalls(DEVICE_CONFIG_CALLS) else 0
        log.info(
            "receivers: $receivers, settings calls: $settings, windows: $windows, reflection: $reflection, guarded: $guarded, " +
                "device config: $deviceConfig",
        )
        if (settings < 20) throw PatchException("only $settings settings calls found")
        if (guarded < 2) throw PatchException("only $guarded content observer / user calls found")
        // Measured against 13: one, Launcher3's DeviceFlag.
        if (android13Build && deviceConfig == 0) throw PatchException("no DeviceConfig reads found")

        // Suggested apps come from Android's app prediction service, which only answers a caller with usage access: each
        // lookup of the service sees none without it (the launcher checks for null), so it skips its predictions.
        val predictionManager = "Landroid/app/prediction/AppPredictionManager;"
        var gated = 0
        for ((type, method) in castSites(setOf(predictionManager))) {
            val m = mutableMethod(type, method)
            val instructions = m.implementation!!.instructions
            for (index in instructions.indices.reversed()) {
                val insn = instructions[index]
                if (insn.opcode != Opcode.CHECK_CAST || ((insn as ReferenceInstruction).reference as TypeReference).type != predictionManager) continue
                val r = (insn as OneRegisterInstruction).registerA
                m.addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$r .. v$r }, $EXT/Launch;->predictions(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$r
                        check-cast v$r, $predictionManager
                    """,
                )
                gated++
            }
        }
        log.info("app prediction lookups: $gated")
        if (gated == 0) throw PatchException("no app prediction service lookups found")

        // Launches animate through a remote animation and the home/close transitions are registered as remote
        // transitions, which only a home app holding CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS (a privileged permission)
        // may do: without it launches use Android's own animation and nothing is registered. (From Xperia Home's port;
        // p0 may be above v15, which iget-object cannot name: it is moved first.)
        val manager = "Lcom/android/launcher3/QuickstepTransitionManager;"
        val wrapper = "Lcom/android/launcher3/util/ActivityOptionsWrapper;"
        val runnables = "Lcom/android/launcher3/util/RunnableList;"
        val transitions = mutableClassDefBy(manager)
        // 15 keeps a QuickstepLauncher, 13 a BaseQuickstepLauncher.
        val launcherField = transitions.fields.firstOrNull { it.name == "mLauncher" }?.let { "$manager->mLauncher:${it.type}" }
            ?: throw PatchException("QuickstepTransitionManager.mLauncher not found")
        val launchOptions = transitions.methods.firstOrNull { it.name == "getActivityLaunchOptions" && it.returnType == wrapper }
            ?: throw PatchException("QuickstepTransitionManager.getActivityLaunchOptions not found")
        if (launchOptions.implementation!!.registerCount - launchOptions.parameterTypes.size - 1 < 3) {
            throw PatchException("getActivityLaunchOptions has too few registers")
        }
        launchOptions.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p0
                iget-object v0, v0, $launcherField
                invoke-static { v0 }, $EXT/Launch;->remoteAnimations(Landroid/content/Context;)Z
                move-result v0
                if-nez v0, :remote
                invoke-static { }, Landroid/app/ActivityOptions;->makeBasic()Landroid/app/ActivityOptions;
                move-result-object v0
                new-instance v1, $runnables
                invoke-direct { v1 }, $runnables-><init>()V
                new-instance v2, $wrapper
                invoke-direct { v2, v0, v1 }, $wrapper-><init>(Landroid/app/ActivityOptions;$runnables)V
                return-object v2
            """,
            ExternalLabel("remote", launchOptions.implementation!!.instructions.first()),
        )
        var registrations = 0
        for (name in listOf(
            "registerRemoteAnimations", "registerRemoteTransitions", "unregisterRemoteAnimations",
            "unregisterRemoteTransitions",
        )) {
            for (method in transitions.methods.filter { it.name == name && it.returnType == "V" }) {
                val parameterRegisters = method.parameterTypes.sumOf { t -> if (t.toString() == "J" || t.toString() == "D") 2 else 1 }
                if (method.implementation!!.registerCount - parameterRegisters - 1 < 1) {
                    throw PatchException("QuickstepTransitionManager.$name has no spare register")
                }
                method.addInstructionsWithLabels(
                    0,
                    """
                        move-object/from16 v0, p0
                        iget-object v0, v0, $launcherField
                        invoke-static { v0 }, $EXT/Launch;->remoteAnimations(Landroid/content/Context;)Z
                        move-result v0
                        if-nez v0, :remote
                        return-void
                    """,
                    ExternalLabel("remote", method.implementation!!.instructions.first()),
                )
                registrations++
            }
        }
        // Measured against 15 and 13: registerRemoteAnimations(boolean), registerRemoteTransitions and their two unregisters.
        if (registrations < 4) throw PatchException("only $registrations remote animation registrations found")

        // Widgets: with background widget updates on, the launcher's holder routes each widget's updates through a
        // listener only the system may register (AppWidgetHost.setListener), and widgets would stay "Loading...". Where
        // Android refuses it the factory takes its own other branch, Launcher3's plain holder. (From Xperia Home's port.)
        // (13's Launcher3 has no background widget host: its widgets get their updates the way any home app does.)
        if (android13Build) return@execute
        val factory = mutableClassDefBy("Lcom/android/launcher3/uioverrides/QuickstepWidgetHolder\$QuickstepHolderFactory;")
        val newInstance = factory.methods.firstOrNull {
            it.name == "newInstance" && it.parameterTypes.size == 3 && it.returnType == "Lcom/android/launcher3/widget/LauncherWidgetHolder;"
        } ?: throw PatchException("QuickstepHolderFactory.newInstance(Context, IntConsumer, InteractionHandler) not found")
        val insns = newInstance.implementation!!.instructions
        val flagIndex = insns.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == "ENABLE_WIDGET_HOST_IN_BACKGROUND"
        }
        if (flagIndex < 0) throw PatchException("ENABLE_WIDGET_HOST_IN_BACKGROUND check not found in the widget holder factory")
        val moveIndex = (flagIndex + 1 until insns.size).firstOrNull { insns[it].opcode == Opcode.MOVE_RESULT }
            ?: throw PatchException("widget holder factory: no move-result after the background flag")
        val flag = (insns[moveIndex] as OneRegisterInstruction).registerA
        if (newInstance.implementation!!.registerCount - 4 < 2) throw PatchException("widget holder factory has no spare register")
        val spare = if (flag == 0) 1 else 0
        newInstance.addInstructions(
            moveIndex + 1,
            """
                invoke-static { }, $EXT/Launch;->widgetListener()Z
                move-result v$spare
                and-int/2addr v$flag, v$spare
            """,
        )
    }
}

/**
 * Moto App Launcher requires Motorola's system libraries (moto-checkin, moto-core_services, moto-settings and the
 * com.motorola.motosignature marker), so Android refuses the install. They become optional; the extension carries
 * stand-ins for the five Motorola classes it calls (Motorola's settings tables kept locally, check-in reports dropped,
 * the cover-screen manager answering "none").
 */
private val librariesPatch = resourcePatch(
    description = "Makes Moto App Launcher's Motorola libraries optional.",
) {
    execute {
        val required = makeLibrariesOptional()
        log.info("required libraries made optional: $required")
        // Measured against 15: four.
        if (required == 0) throw PatchException("no required library found")
    }
}

/** Another version of the app or too old an Android stops here with a clear message (see supportedVersionCheck). */
private val versionCheckPatch = supportedVersionCheck(COMPATIBILITY_MOTO_LAUNCHER)

/**
 * Off Motorola, Moto App Launcher is refused at install (it requires Motorola's libraries) and then dies on Android's
 * hidden API, which only a system app may use. Recents and navigation gestures stay the phone's own: Android gives
 * them only to the system's home app.
 */
@Suppress("unused")
val enableNonMotorolaPatch = bytecodePatch(
    name = "Enable non-Motorola ROM support",
    description = "Makes Moto App Launcher usable as a home screen on non-Motorola ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MOTO_LAUNCHER)
    dependsOn(versionCheckPatch, librariesPatch, hiddenApisPatch, systemPrivilegesPatch)
    extendWith(MOTO_LAUNCHER_EXTENSION)
    dependsOn(org.ungoogled.patches.shared.launcherCoexistencePatch())
}
