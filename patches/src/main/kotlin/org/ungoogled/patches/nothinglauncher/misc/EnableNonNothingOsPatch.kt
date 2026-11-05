package org.ungoogled.patches.nothinglauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.nothinglauncher.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.nothinglauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.nothinglauncher.NothingLauncherConstants.COMPATIBILITY_NOTHING_LAUNCHER
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectMembers

internal const val NOTHING_LAUNCHER_EXTENSION = "extensions/nothinglauncher.mpe"

private const val EXT = "Lorg/ungoogled/nothinglauncher"
private const val ACTIVITY_OPTIONS = "Landroid/app/ActivityOptions;"

private val log = java.util.logging.Logger.getLogger("NothingLauncher")

private const val BINDER = "Landroid/os/Binder;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * Android classes the launcher extends whose constructor an installed app may not call: the public class each subclass
 * is rebased onto, and the interfaces it keeps. Subclasses only debug builds or TVs create (view capture tracing, TV
 * picture-in-picture) keep their base: they are never created here.
 */
private val REBASES = mapOf(
    "Landroid/content/IIntentSender\$Stub;" to (BINDER to listOf("Landroid/content/IIntentSender;")),
    "Landroid/view/IRemoteAnimationFinishedCallback\$Stub;" to (BINDER to listOf("Landroid/view/IRemoteAnimationFinishedCallback;")),
    "Landroid/view/ISystemGestureExclusionListener\$Stub;" to (BINDER to listOf("Landroid/view/ISystemGestureExclusionListener;")),
    "Landroid/window/IOnBackInvokedCallback\$Stub;" to (BINDER to listOf("Landroid/window/IOnBackInvokedCallback;")),
    "Landroid/window/IRemoteTransitionFinishedCallback\$Stub;" to (BINDER to listOf("Landroid/window/IRemoteTransitionFinishedCallback;")),
    "Landroid/window/RemoteTransitionStub;" to (BINDER to listOf("Landroid/window/IRemoteTransition;")),
    "Lcom/android/internal/os/IResultReceiver\$Stub;" to (BINDER to listOf("Lcom/android/internal/os/IResultReceiver;")),
    "Landroid/view/CompositionSamplingListener;" to (OBJECT to emptyList()),
    "Landroid/view/WindowlessWindowManager;" to (OBJECT to emptyList()),
    "Landroid/widget/TextClock\$ClockEventDelegate;" to (OBJECT to emptyList()),
)

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
    "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I" to "GetIntForUser",
    "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;" to "GetStringForUser",
    "putIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)Z" to "PutIntForUser",
    "putStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;I)Z" to "PutStringForUser",
)

/** Every settings call goes through LocalSettings, which keeps a refused write in the app's own preferences. */
private val SETTINGS_CALLS: Map<String, String> = buildMap {
    for (table in listOf("Global", "Secure", "System")) {
        for ((shape, suffix) in SETTINGS_SHAPES) {
            if (table == "Global" && suffix.endsWith("ForUser")) continue
            put(
                "Landroid/provider/Settings\$$table;->$shape",
                "$EXT/LocalSettings;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}",
            )
        }
    }
}

/** Context.getDisplay() on contexts that are not visual (see Windows). */
private val DISPLAY_CALLS = listOf("Landroid/content/Context;", "Landroid/content/ContextWrapper;").associate { owner ->
    "$owner->getDisplay()Landroid/view/Display;" to "$EXT/Windows;->getDisplay(${owner})Landroid/view/Display;"
}

/**
 * Nothing Launcher is a system app built against the full Android framework: an installed app may not link to its
 * hidden parts. Each hidden member it uses goes through Members (used where Android allows it, "absent" elsewhere),
 * each `new` of a class an app may not construct through Members.construct, and subclasses of such classes are
 * rebased. Its settings are kept locally where Android refuses the write; where the system launcher's own code needs
 * what only the system has (window size from the configuration, a shared clock listener, wallpaper color science),
 * Launcher3's or the platform's ordinary answers are used.
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(NOTHING_LAUNCHER_EXTENSION)

    execute {
        // On a debuggable ROM the launcher records its views for debugging through system-only tracing (a window
        // manager and trace sources only the system may create); it now takes its release path there too.
        val viewCapture = mutableListOf<Pair<String, String>>()
        classDefForEach { c ->
            for (m in c.methods) {
                if (m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "PerfettoViewCapture"
                    } == true
                ) viewCapture += c.type to "${m.name}${m.parameterTypes.joinToString("", "(", ")")}${m.returnType}"
            }
        }
        var debuggable = 0
        for ((type, signature) in viewCapture) {
            val method = mutableClassDefBy(type).methods.first {
                "${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" == signature
            }
            val instructions = method.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                val f = (insn as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                if (insn.opcode == Opcode.SGET_BOOLEAN && f.definingClass == "Landroid/os/Build;" && f.name == "IS_DEBUGGABLE") {
                    method.replaceInstruction(index, "const/4 v${(insn as OneRegisterInstruction).registerA}, 0x0")
                    debuggable++
                }
            }
        }
        // Measured against 4.0.20: one check, in the view capture factory.
        if (debuggable == 0) throw PatchException("view capture debuggable check not found")

        // Settings first: some of its calls are hidden per-user ones, which LocalSettings answers for the app's user.
        val settings = redirectCalls(SETTINGS_CALLS)
        val displays = redirectCalls(DISPLAY_CALLS)
        log.info("display lookups: $displays")
        // Measured against 4.0.20: 32, the home screen preview's (Customization > Home screen layout) among them.
        if (displays == 0) throw PatchException("no Context.getDisplay calls found")
        val (calls, fields) = redirectMembers("$EXT/Members;", "$EXT/Hidden;", HIDDEN_MEMBERS) { _, _ -> false }
        val constructors = redirectConstructors("$EXT/Members;", "$EXT/Hidden;", HIDDEN_CONSTRUCTORS)
        val rebased = rebaseSubclasses(REBASES)
        skipCastOfNull("$EXT/Hidden;")
        log.info("settings calls: $settings; hidden members: $calls calls, $fields fields; constructors: $constructors; rebased: $rebased")
        // Measured against 4.0.20: 63 settings calls, 1,446 calls, 332 field accesses, 90 constructors, 20 rebased classes.
        if (settings < 10) throw PatchException("only $settings settings calls found")
        if (calls < 100) throw PatchException("only $calls hidden member calls found")
        if (constructors == 0 || rebased == 0) throw PatchException("hidden constructors not found")

        // Clocks in widgets share one time-tick listener, a kind of object only the system may create: they keep
        // their own, as in any other app.
        val launcher = mutableClassDefBy("Lcom/android/launcher3/uioverrides/QuickstepLauncher;")
        val createView = launcher.methods.firstOrNull {
            it.name == "onCreateView" && it.parameterTypes.size == 4 && it.returnType == "Landroid/view/View;"
        } ?: throw PatchException("QuickstepLauncher.onCreateView not found")
        createView.addInstructionsWithLabels(
            0,
            """
                invoke-static { p2 }, $EXT/Launch;->ownClock(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :launcher
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("launcher", createView.implementation!!.instructions.first()),
        )

        // Widget colors from the wallpaper are computed with Android's hidden color-science classes (Cam, the
        // wallpaper's full color histogram): without them widgets keep their own colors, as in other home apps.
        val colors = mutableClassDefBy("Lcom/nothing/launcher/widget/LocalWallpaperColorsExtractor;").methods.firstOrNull {
            it.name == "generateColorsOverride" && it.returnType == "Landroid/util/SparseIntArray;"
        } ?: throw PatchException("LocalWallpaperColorsExtractor.generateColorsOverride not found")
        colors.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXT/Launch;->wallpaperColors()Z
                move-result v0
                if-nez v0, :colors
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("colors", colors.implementation!!.instructions.first()),
        )

        // The system launcher reads the window's size, rotation and cutout from hidden parts of the configuration;
        // Launcher3's own answers, made for launchers that are not the system's, are used instead.
        val proxy = mutableClassDefBy("Lcom/android/quickstep/util/SystemWindowManagerProxy;")
        val own = setOf(
            "estimateInternalDisplayBounds", "getCurrentBounds", "getRotation", "getStatusBarHeight", "rotateCutout",
            "showDesktopTaskbarForFreeformDisplay", "showLockedTaskbarOnHome",
        )
        val before = proxy.methods.size
        proxy.methods.removeIf { it.name in own }
        if (before - proxy.methods.size != own.size) {
            throw PatchException("SystemWindowManagerProxy: ${before - proxy.methods.size} of ${own.size} methods found")
        }
    }
}

/**
 * What a system home screen may do and an installed app may not: launch apps with its own remote animation, ask the
 * app prediction service for suggestions, receive widget updates through a system-only listener.
 */
private val systemPrivilegesPatch = bytecodePatch(
    description = "Uses Android's own launch animation and widget updates, and skips app suggestions where Android refuses them.",
) {
    extendWith(NOTHING_LAUNCHER_EXTENSION)

    execute {
        val manager = "Lcom/android/launcher3/QuickstepTransitionManager;"
        val wrapper = "Lcom/android/launcher3/util/ActivityOptionsWrapper;"
        val runnables = "Lcom/android/launcher3/util/RunnableList;"
        val launchOptions = mutableClassDefBy(manager).methods.firstOrNull {
            it.name == "getActivityLaunchOptions" && it.parameterTypes.size == 2 && it.returnType == wrapper
        } ?: throw PatchException("QuickstepTransitionManager.getActivityLaunchOptions not found")
        launchOptions.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $manager->mLauncher:Lcom/android/launcher3/uioverrides/QuickstepLauncher;
                invoke-static { v0 }, $EXT/Launch;->remoteAnimations(Landroid/content/Context;)Z
                move-result v0
                if-nez v0, :remote
                invoke-static { }, $ACTIVITY_OPTIONS->makeBasic()$ACTIVITY_OPTIONS
                move-result-object v0
                new-instance v1, $runnables
                invoke-direct { v1 }, $runnables-><init>()V
                new-instance v2, $wrapper
                invoke-direct { v2, v0, v1 }, $wrapper-><init>($ACTIVITY_OPTIONS$runnables)V
                return-object v2
            """,
            ExternalLabel("remote", launchOptions.implementation!!.instructions.first()),
        )

        // Suggested apps come from Android's app prediction service, which only answers a caller with usage access: each
        // lookup of the service sees none without it, so the launcher skips its predictions instead of crashing.
        val predictionManager = "Landroid/app/prediction/AppPredictionManager;"
        val casts = mutableListOf<Pair<String, String>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any {
                        it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == predictionManager
                    } == true
                ) casts += c.type to "${m.name}${m.parameterTypes.joinToString("", "(", ")")}${m.returnType}"
            }
        }
        var gated = 0
        for ((type, signature) in casts) {
            val method = mutableClassDefBy(type).methods.first {
                "${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" == signature
            }
            val instructions = method.implementation!!.instructions
            for (index in instructions.indices.reversed()) {
                val insn = instructions[index]
                if (insn.opcode != Opcode.CHECK_CAST || ((insn as ReferenceInstruction).reference as TypeReference).type != predictionManager) continue
                val r = (insn as OneRegisterInstruction).registerA
                method.addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$r .. v$r }, $EXT/Launch;->predictions($OBJECT)$OBJECT
                        move-result-object v$r
                        check-cast v$r, $predictionManager
                    """,
                )
                gated++
            }
        }
        log.info("app prediction lookups: $gated")
        // Measured against 4.0.20: 4 lookups.
        if (gated == 0) throw PatchException("no app prediction service lookups found")

        // Widgets: the system launcher's widget holder routes each widget's updates through a listener only the system
        // may register, so every widget stayed "Loading...". Without it the launcher uses Launcher3's own holder, which
        // gets its updates the way any home app does (same widget host id, 1024).
        val holder = "Lcom/android/launcher3/widget/LauncherWidgetHolder;"
        val factory = mutableClassDefBy("Lcom/android/launcher3/uioverrides/QuickstepWidgetHolder_QuickstepWidgetHolderFactory_Impl;")
        val bridge = factory.methods.firstOrNull {
            it.name == "newInstance" && it.returnType == holder && it.parameterTypes.map { t -> t.toString() } == listOf("Landroid/content/Context;")
        } ?: throw PatchException("QuickstepWidgetHolder factory newInstance not found")
        factory.methods.remove(bridge)
        factory.methods.add(
            ImmutableMethod(
                bridge.definingClass, bridge.name, listOf(ImmutableMethodParameter("Landroid/content/Context;", null, null)),
                bridge.returnType, bridge.accessFlags, null, null, MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, $EXT/Launch;->widgetListener()Z
                        move-result v0
                        if-eqz v0, :own
                        invoke-virtual { p0, p1 }, ${factory.type}->newInstance(Landroid/content/Context;)Lcom/android/launcher3/uioverrides/QuickstepWidgetHolder;
                        move-result-object v0
                        return-object v0
                        :own
                        new-instance v0, $holder
                        const/16 v1, 0x400
                        invoke-direct { v0, p1, v1 }, $holder-><init>(Landroid/content/Context;I)V
                        return-object v0
                    """,
                )
            },
        )

        // The home screen menu's "Customization" opens Nothing's wallpaper app, which links to the launcher's own
        // Customization page (grid, icon pack, wallpaper scrolling). Without that app it opens that page directly. The
        // menu item ends in a helper that builds the wallpaper intent (the one with "app_launched_launcher").
        val jumpers = mutableListOf<String>()
        classDefForEach { c ->
            if (c.type != "Lcom/android/launcher3/views/OptionsPopupView;" && c.methods.any { m ->
                    m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "app_launched_launcher"
                    } == true
                }
            ) jumpers += c.type
        }
        // Measured against 4.0.20: one, Nothing's activity starter (Launcher3's own OptionsPopupView copy is unused).
        val jumperType = jumpers.singleOrNull()
            ?: throw PatchException("expected one wallpaper intent helper (app_launched_launcher), found $jumpers")
        val jumperClass = mutableClassDefBy(jumperType)
        val wallpaperIntent = jumperClass.methods.firstOrNull { m ->
            m.returnType == "Landroid/content/Intent;" && m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "app_launched_launcher"
            } == true
        } ?: throw PatchException("wallpaper intent builder not found in $jumperType")
        val menuEntry = jumperClass.methods.filter { m ->
            m.returnType == "V" && m.parameterTypes.map { it.toString() } == listOf("Landroid/view/View;") &&
                m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)
                        ?.let { r -> r.definingClass == jumperType && r.name == wallpaperIntent.name } == true
                } == true
        }
        // Measured against 4.0.20: one, the menu's (the Customization page's own entry takes a Context).
        if (menuEntry.size != 1) throw PatchException("expected one Customization menu entry in $jumperType, found ${menuEntry.size}")
        val entry = menuEntry.single()
        val view = if (com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(entry.accessFlags)) "p0" else "p1"
        entry.addInstructionsWithLabels(
            0,
            """
                invoke-static { $view }, $EXT/Launch;->customization(Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :nothing
                return-void
            """,
            ExternalLabel("nothing", entry.implementation!!.instructions.first()),
        )

        // The launcher's own home/close transitions are registered with Android as remote animations and transitions,
        // which likewise needs the permission: without it they are not registered (Android's own animations play).
        val transitions = mutableClassDefBy(manager)
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
                    iget-object v0, p0, $manager->mLauncher:Lcom/android/launcher3/uioverrides/QuickstepLauncher;
                    invoke-static { v0 }, $EXT/Launch;->remoteAnimations(Landroid/content/Context;)Z
                    move-result v0
                    if-nez v0, :remote
                    return-void
                """,
                ExternalLabel("remote", method.implementation!!.instructions.first()),
            )
        }
    }
}

/**
 * Smart App Drawer sorts apps into categories from a database it carries, and asks Nothing's server about the apps that
 * database lacks, sending the app list. Off Nothing OS nobody agreed to that: the server is no longer asked (it answers
 * "nothing new"), and such apps go to "Others".
 */
private val offlinePatch = bytecodePatch(
    description = "Keeps the launcher offline: Smart App Drawer sorts apps on the phone only.",
) {
    execute {
        // The Retrofit interface method annotated with the category endpoint.
        var query: Pair<String, String>? = null
        classDefForEach { c ->
            for (m in c.methods) {
                val annotated = m.annotations.any { a ->
                    a.elements.any { e ->
                        val v = e.value
                        (v is com.android.tools.smali.dexlib2.iface.value.StringEncodedValue && v.value == "v1/app/query") ||
                            (v is com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue && v.value.any {
                                it is com.android.tools.smali.dexlib2.iface.value.StringEncodedValue && it.value == "v1/app/query"
                            })
                    }
                }
                if (annotated) query = c.type to m.name
            }
        }
        val (api, name) = query ?: throw PatchException("Smart App Drawer category request (v1/app/query) not found")
        val callers = mutableListOf<Pair<String, String>>()
        classDefForEach { c ->
            if (c.type == api) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.MethodReference)
                            ?.let { r -> r.definingClass == api && r.name == name } == true
                    } == true
                ) callers += c.type to "${m.name}${m.parameterTypes.joinToString("", "(", ")")}${m.returnType}"
            }
        }
        // Measured against 4.0.20: one caller, a suspend function answering a Map of package name to category.
        if (callers.size != 1) throw PatchException("expected one category request caller, found ${callers.size}")
        val (type, signature) = callers.single()
        val method = mutableClassDefBy(type).methods.first {
            "${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" == signature
        }
        if (method.returnType != OBJECT) throw PatchException("category request caller $type returns ${method.returnType}")
        method.addInstructions(
            0,
            """
                new-instance v0, Ljava/util/LinkedHashMap;
                invoke-direct { v0 }, Ljava/util/LinkedHashMap;-><init>()V
                return-object v0
            """,
        )
    }
}

/**
 * Off Nothing OS the launcher dies at start: it reflects on Nothing OS framework classes only the ROM has
 * (com.nothing.NtFeaturesUtils, the online-config reader) and calls Nothing's analytics client. The extension carries
 * stand-ins that answer "feature absent" / "no online config" and send nothing; on a Nothing phone the framework's own
 * classes load first, so they never run there. Recents and navigation gestures stay the phone's own: Android gives
 * them only to the system's home app.
 */
@Suppress("unused")
val enableNonNothingOsPatch = bytecodePatch(
    name = "Enable non-Nothing OS ROM support",
    description = "Makes Nothing Launcher usable as a home screen on non-Nothing OS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NOTHING_LAUNCHER)
    dependsOn(hiddenApisPatch, systemPrivilegesPatch, offlinePatch)
    extendWith(NOTHING_LAUNCHER_EXTENSION)
}
