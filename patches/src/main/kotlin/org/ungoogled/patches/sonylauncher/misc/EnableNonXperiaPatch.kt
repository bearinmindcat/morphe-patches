package org.ungoogled.patches.sonylauncher.misc

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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.coloroslauncher.misc.rebaseSubclasses
import org.ungoogled.patches.coloroslauncher.misc.redirectConstructors
import org.ungoogled.patches.coloroslauncher.misc.redirectHiddenMembers
import org.ungoogled.patches.coloroslauncher.misc.skipCastOfNull
import org.ungoogled.patches.coloroslauncher.misc.substituteSuperConstructors
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.sony.evenResourceEntryCounts
import org.ungoogled.patches.sony.replacePrivateThemeColors
import org.ungoogled.patches.sonylauncher.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.sonylauncher.HIDDEN_CONSTRUCTORS_16_17
import org.ungoogled.patches.sonylauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.sonylauncher.HIDDEN_MEMBERS_16_17
import org.ungoogled.patches.sonylauncher.HIDDEN_SUPERCLASSES
import org.ungoogled.patches.sonylauncher.SUPER_CONSTRUCTORS
import org.ungoogled.patches.sonylauncher.SonyLauncherConstants.COMPATIBILITY_XPERIA_HOME

internal const val XPERIA_HOME_EXTENSION = "extensions/sonylauncher.mpe"

private const val EXT = "Lorg/ungoogled/sonylauncher"

private val log = java.util.logging.Logger.getLogger("XperiaHome")

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
    // Sony's theme overlays for the launcher (Xperia's per-model resource overlays), which only the system may list.
    "Landroid/content/om/OverlayManager;->getOverlayInfosForTarget(Ljava/lang/String;Landroid/os/UserHandle;)Ljava/util/List;" to
        "$EXT/Calls;->getOverlayInfosForTarget(Landroid/content/om/OverlayManager;Ljava/lang/String;Landroid/os/UserHandle;)Ljava/util/List;",
)

/**
 * Xperia Home requires Sony's com.sonyericsson.idd library (its usage-data client), so Android refuses the install.
 * Its layouts and styles also name Android 15's private theme colors, which Android 16 and 17 no longer have (see
 * replacePrivateThemeColors). Changing resources makes Morphe rewrite the resource table, which for this app needs
 * even entry counts to stay loadable (see evenResourceEntryCounts).
 */
private val librariesPatch = resourcePatch(
    description = "Makes Xperia Home's Sony library optional and its theme colors work on Android 16 and 17.",
) {
    execute {
        val required = makeLibrariesOptional()
        val colors = replacePrivateThemeColors()
        val padded = evenResourceEntryCounts()
        log.info(
            "required libraries made optional: $required; private theme colors: $colors; " +
                "resource types padded to even counts: $padded",
        )
        // Measured against 15: one, com.sonyericsson.idd.
        if (required == 0) throw PatchException("no required library found")
        // Measured against 15: 74 references (the overview's clear-all button stopped the launcher on Android 16).
        if (colors == 0) throw PatchException("no private theme colors found")
    }
}

/**
 * Android classes the launcher extends that hidden.py does not list (their constructor is not the one Android 15 has):
 * rebased like the generated ones (see rebaseSubclasses). From the Nothing Launcher port.
 */
private val EXTRA_SUPERCLASSES = mapOf(
    "Landroid/window/RemoteTransitionStub;" to ("Landroid/os/Binder;" to listOf("Landroid/window/IRemoteTransition;")),
)

/**
 * Xperia Home is a system app built against the full Android framework; an installed app may not link to its hidden
 * parts. Each hidden member it uses goes through Members (used where Android allows it, "absent" elsewhere), each
 * `new` of a class an app may not construct through Members.construct, and subclasses of such classes are rebased.
 * The lists are generated (.github/scripts/coloros-launcher/hidden.py), the helpers are the OnePlus System Launcher
 * port's (the same Launcher3 / Quickstep base on Android 15).
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(XPERIA_HOME_EXTENSION)

    execute {
        // On a debuggable ROM the launcher records its views for debugging through Perfetto data sources, classes only
        // the system may create: it takes its release path there too, and those classes keep their Android base (they
        // are never created; rebased they would no longer verify).
        val viewCapture = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            for (m in c.methods) {
                if (m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "PerfettoViewCapture"
                    } == true
                ) viewCapture += c.type to m
            }
        }
        var debuggable = 0
        for ((type, method) in viewCapture) {
            val m = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = m.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                val f = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                if (insn.opcode == Opcode.SGET_BOOLEAN && f.definingClass == "Landroid/os/Build;" && f.name == "IS_DEBUGGABLE") {
                    m.replaceInstruction(index, "const/4 v${(insn as OneRegisterInstruction).registerA}, 0x0")
                    debuggable++
                }
            }
        }
        // Measured against 15: one check, in the view capture factory.
        if (debuggable == 0) throw PatchException("view capture debuggable check not found")

        val (calls, fields) = redirectHiddenMembers(
            "$EXT/Members;", "$EXT/Hidden;", HIDDEN_MEMBERS + HIDDEN_MEMBERS_16_17,
        )
        val constructors = redirectConstructors(
            "$EXT/Members;", "$EXT/Hidden;", HIDDEN_CONSTRUCTORS + HIDDEN_CONSTRUCTORS_16_17,
        )
        val superCalls = substituteSuperConstructors(SUPER_CONSTRUCTORS)
        val bases = HIDDEN_SUPERCLASSES.filterKeys { !it.startsWith("Landroid/tracing/perfetto/") } + EXTRA_SUPERCLASSES
        val rebased = rebaseSubclasses(bases)
        val casts = nullCastsToRebasedBases(bases.keys)
        skipCastOfNull("$EXT/Hidden;")
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; super calls: $superCalls; rebased classes: $rebased; casts to their old bases: $casts")
        // Measured against 15: 905 calls, 405 fields, 181 constructors, 1 super call, 16 rebased classes, 4 casts.
        if (calls < 100) throw PatchException("only $calls hidden member calls found")
        if (constructors == 0) throw PatchException("hidden constructors not found")
        if (rebased == 0) throw PatchException("no subclasses of hidden Android classes found")
    }
}

/**
 * A rebased object (see rebaseSubclasses) is no longer of its old Android base class, so a cast to that class throws
 * (GestureExclusionManager casts its gesture-exclusion listener, a binder stub only the system may create, to the stub
 * class before keeping it). rebaseSubclasses leaves fields of that type empty; the cast now gives null too. Returns the
 * casts changed.
 */
private fun BytecodePatchContext.nullCastsToRebasedBases(bases: Set<String>): Int {
    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any {
                    it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type in bases
                } == true
            ) sites += c.type to m
        }
    }
    var changed = 0
    for ((type, method) in sites) {
        val m = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
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
 * receivers without saying who may send, animate launches remotely, ask the app prediction service, get widget updates
 * through a system-only listener.
 */
private val systemPrivilegesPatch = bytecodePatch(
    description = "Keeps its options locally and uses Android's own launch animations and widget updates.",
) {
    extendWith(XPERIA_HOME_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers("$EXT/Receivers;")
        val settings = redirectCalls(SETTINGS_CALLS)
        val windows = redirectCalls(WINDOW_CALLS)
        val reflection = redirectCalls(REFLECTION_CALLS)
        val guarded = redirectCalls(GUARDED_CALLS)
        log.info("receivers: $receivers, settings calls: $settings, windows: $windows, reflection: $reflection, guarded: $guarded")
        // Measured against 15: 18 receivers, 30 settings calls, 34 window calls, 396 reflection calls, 12 guarded calls.
        if (settings < 20) throw PatchException("only $settings settings calls found")
        if (guarded < 5) throw PatchException("only $guarded content observer / user / overlay calls found")

        // Launches animate through a remote animation and the home/close transitions are registered as remote
        // transitions, which only a home app holding CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS (a privileged permission)
        // may do: without it launches use Android's own animation and nothing is registered. (p0 may be above v15,
        // which iget-object cannot name: it is moved first.)
        val manager = "Lcom/android/launcher3/QuickstepTransitionManager;"
        val wrapper = "Lcom/android/launcher3/util/ActivityOptionsWrapper;"
        val runnables = "Lcom/android/launcher3/util/RunnableList;"
        val launcherField = "$manager->mLauncher:Lcom/android/launcher3/uioverrides/QuickstepLauncher;"
        val transitions = mutableClassDefBy(manager)
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
        for (name in listOf(
            "registerRemoteAnimations", "registerRemoteTransitions", "unregisterRemoteAnimations",
            "unregisterRemoteTransitions",
        )) {
            val method = transitions.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }
                ?: throw PatchException("QuickstepTransitionManager.$name not found")
            if (method.implementation!!.registerCount < 2) throw PatchException("QuickstepTransitionManager.$name has no spare register")
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
        }

        // Suggested apps come from Android's app prediction service, which only answers a caller with usage access: each
        // lookup of the service sees none without it (the launcher checks for null), so it skips its predictions.
        val predictionManager = "Landroid/app/prediction/AppPredictionManager;"
        val casts = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any {
                        it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == predictionManager
                    } == true
                ) casts += c.type to m
            }
        }
        var gated = 0
        for ((type, method) in casts) {
            val m = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
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
        // Measured against 15: 3 lookups (all apps + hotseat, hotseat, widget suggestions).
        if (gated == 0) throw PatchException("no app prediction service lookups found")

        // Widgets: with background widget updates on, the launcher's holder routes each widget's updates through a
        // listener only the system may register (AppWidgetHost.setListener), and widgets would stay "Loading...". Where
        // Android refuses it the factory takes its own other branch, Launcher3's plain holder, which gets updates the
        // way any home app does.
        val factory = mutableClassDefBy("Lcom/android/launcher3/uioverrides/QuickstepWidgetHolder\$QuickstepHolderFactory;")
        val newInstance = factory.methods.firstOrNull {
            it.name == "newInstance" && it.parameterTypes.size == 3 && it.returnType == "Lcom/android/launcher3/widget/LauncherWidgetHolder;"
        } ?: throw PatchException("QuickstepHolderFactory.newInstance(Context, IntConsumer, InteractionHandler) not found")
        val insns = newInstance.implementation!!.instructions
        val flagIndex = insns.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)
                ?.name == "ENABLE_WIDGET_HOST_IN_BACKGROUND"
        }
        if (flagIndex < 0) throw PatchException("ENABLE_WIDGET_HOST_IN_BACKGROUND check not found in the widget holder factory")
        val moveIndex = (flagIndex + 1 until insns.size).firstOrNull { insns[it].opcode == Opcode.MOVE_RESULT }
            ?: throw PatchException("widget holder factory: no move-result after the background flag")
        val flag = (insns[moveIndex] as OneRegisterInstruction).registerA
        val locals = newInstance.implementation!!.registerCount - 4
        if (locals < 2) throw PatchException("widget holder factory has no spare register")
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
 * Sony's usage reports: Xperia Home hands launcher events and a periodic snapshot of its settings to Sony's Idd client
 * (com.sonyericsson.idd.api.Idd, a system library, here a stand-in that drops them). The launcher's Idd manager now
 * reports Idd as unsupported, so it builds no events, and the daily report job is not scheduled.
 */
private val telemetryPatch = bytecodePatch(
    description = "Sends nothing to Sony's usage reports.",
) {
    extendWith(XPERIA_HOME_EXTENSION)

    execute {
        fun strings(m: Method) = m.implementation?.instructions
            ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()

        // The manager probes for Idd.addAppDataJSON by reflection, by name.
        val managers = mutableListOf<Pair<String, String>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.returnType != "Z" || m.parameterTypes.isNotEmpty()) continue
                val s = strings(m)
                if ("com.sonyericsson.idd.api.Idd" in s && "addAppDataJSON" in s) managers += c.type to m.name
            }
        }
        // Measured against 15: one, IddManager.isIddSupported (the class name and method name are its constants).
        val (managerType, probeName) = managers.singleOrNull()
            ?: throw PatchException("expected one Idd support probe, found $managers")
        val probe = mutableClassDefBy(managerType).methods.first { it.name == probeName && it.returnType == "Z" && it.parameterTypes.isEmpty() }
        probe.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // The daily report job (JobScheduler job 128 for IddJobService): never scheduled.
        val jobType = "Lcom/sonymobile/launcher/idd/IddJobService;"
        val job = mutableClassDefByOrNull(jobType) ?: throw PatchException("IddJobService not found")
        val schedules = job.methods.filter {
            it.name == "schedule" && it.returnType == "V" && it.parameterTypes.firstOrNull()?.toString() == "Landroid/content/Context;"
        }
        // Measured against 15: schedule(Context) and schedule(Context, Launcher).
        if (schedules.isEmpty()) throw PatchException("IddJobService.schedule not found")
        for (m in schedules) m.addInstructions(0, "return-void")
        log.info("Idd probe: $managerType->$probeName; report job schedules: ${schedules.size}")
    }
}

/**
 * Off Xperia, Xperia Home is refused at install (it requires Sony's Idd library) and then dies on Android's hidden API,
 * which only a system app may use. The extension carries a stand-in of the Sony class it calls (generated by
 * .github/scripts/sony-standins: Idd, which drops every report); the hidden API goes through Members (see
 * hiddenApisPatch). Recents and navigation gestures stay the phone's own: Android gives them only to the system's home
 * app.
 */
@Suppress("unused")
val enableNonXperiaPatch = bytecodePatch(
    name = "Enable non-Xperia ROM support",
    description = "Makes Xperia Home usable as a home screen on non-Xperia ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_XPERIA_HOME)
    dependsOn(librariesPatch, hiddenApisPatch, systemPrivilegesPatch, telemetryPatch)
    extendWith(XPERIA_HOME_EXTENSION)
}
