package org.ungoogled.patches.samsunglauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.coloroslauncher.misc.redirectHiddenMembers
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsunglauncher.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.samsunglauncher.HIDDEN_CONSTRUCTORS_16_17
import org.ungoogled.patches.samsunglauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.samsunglauncher.HIDDEN_MEMBERS_16_17
import org.ungoogled.patches.samsunglauncher.SamsungLauncherConstants.COMPATIBILITY_SAMSUNG_LAUNCHER

internal const val SAMSUNG_LAUNCHER_EXTENSION = "extensions/samsunglauncher.mpe"

private const val EXT = "Lorg/ungoogled/samsunglauncher"
private const val ACTIVITY_OPTIONS = "Landroid/app/ActivityOptions;"

private val log = java.util.logging.Logger.getLogger("OneUiHome")

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

/** Widget binds: refused ones open Android's widget permission dialog (see Widgets). */
private val WIDGET_BINDS = listOf(
    "(ILandroid/content/ComponentName;)Z",
    "(ILandroid/content/ComponentName;Landroid/os/Bundle;)Z",
    "(ILandroid/os/UserHandle;Landroid/content/ComponentName;Landroid/os/Bundle;)Z",
).associate { shape ->
    "Landroid/appwidget/AppWidgetManager;->bindAppWidgetIdIfAllowed$shape" to
        "$EXT/Widgets;->bind(Landroid/appwidget/AppWidgetManager;${shape.substring(1)}"
}

/**
 * The widget permission dialog's host activity. Some icons are SPR files, Samsung's vector format that only One UI
 * can draw: they become vector drawables.
 */
private val resourcesPatch = resourcePatch(
    description = "Adds the widget permission screen and converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        log.info("SPR drawables: $converted")
        // Measured against 18.0.07.59: 9 files.
        if (converted == 0) throw PatchException("no SPR drawables found")

        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0)
                ?: throw PatchException("no <application> in the manifest")
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", "org.ungoogled.samsunglauncher.WidgetPermissionActivity")
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:excludeFromRecents", "true")
            activity.setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
            application.appendChild(activity)
        }
    }
}

/**
 * System service calls that need a permission only the system holds (INTERACT_ACROSS_USERS); the member helper answers
 * the SecurityException with "absent" (user 0).
 */
private val REFUSED_CALLS = setOf(
    "Landroid/app/IActivityManager;->getCurrentUser()Landroid/content/pm/UserInfo;",
    "Landroid/app/ActivityManager;->getCurrentUser()I",
)

/** Sends One UI Home's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_LAUNCHER_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        log.info("One UI members: $calls calls, $fields fields")
        // Measured against 18.0.07.59: 314 calls, 81 field accesses.
        if (calls < 50) throw PatchException("only $calls One UI calls found")
    }
}

private const val BINDER = "Landroid/os/Binder;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * Android classes One UI Home extends whose constructor an installed app may not call, or that Android 15 lacks: the
 * public class each subclass is rebased onto, and the interfaces it keeps.
 */
private val REBASES = mapOf(
    "Landroid/view/IDisplayWindowListener\$Stub;" to (BINDER to listOf("Landroid/view/IDisplayWindowListener;")),
    "Landroid/view/IRemoteAnimationFinishedCallback\$Stub;" to (BINDER to listOf("Landroid/view/IRemoteAnimationFinishedCallback;")),
    "Landroid/window/RemoteTransitionStub;" to (BINDER to listOf("Landroid/window/IRemoteTransition;")),
    "Landroid/window/IRemoteTransitionFinishedCallback\$Stub;" to (BINDER to listOf("Landroid/window/IRemoteTransitionFinishedCallback;")),
    "Landroid/window/IRemoteTransition\$Stub;" to (BINDER to listOf("Landroid/window/IRemoteTransition;")),
    "Landroid/media/IVolumeController\$Stub;" to (BINDER to listOf("Landroid/media/IVolumeController;")),
    "Lcom/android/internal/os/IResultReceiver\$Stub;" to (BINDER to listOf("Lcom/android/internal/os/IResultReceiver;")),
    "Landroid/window/IOnBackInvokedCallback\$Stub;" to (BINDER to listOf("Landroid/window/IOnBackInvokedCallback;")),
    "Landroid/view/IRemoteAnimationTargetAppearedCallback\$Stub;" to (BINDER to emptyList()),
    "Landroid/sec/enterprise/adapterlayer/ISystemUIAdapterCallback\$Stub;" to
        (BINDER to listOf("Landroid/sec/enterprise/adapterlayer/ISystemUIAdapterCallback;")),
    "Landroid/hardware/devicestate/DeviceStateManager\$FoldStateListener;" to
        (OBJECT to listOf("Landroid/hardware/devicestate/DeviceStateManager\$DeviceStateCallback;")),
    "Landroid/view/CompositionSamplingListener;" to (OBJECT to emptyList()),
    "Landroid/window/TaskSnapshotListener;" to (OBJECT to emptyList()),
    "Landroid/view/WindowManagerWrapper;" to (OBJECT to emptyList()),
    "Landroid/view/HardwareCanvas;" to ("Landroid/graphics/Canvas;" to emptyList()),
)

/**
 * One UI Home is a system app built against the full Android framework; an installed app may not link to its hidden
 * parts. Each hidden member it uses goes through OneUi (used where Android allows it, "absent" elsewhere), each
 * `new` of a class an app may not construct through OneUi.construct, and subclasses of such classes are rebased.
 * The lists cover Android 15, 16 and 17, and the generated helpers take objects untyped: on Android 16+ a rebased
 * listener (TaskSnapshotListener) no longer verified as the class the call named, and the launcher did not start.
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(SAMSUNG_LAUNCHER_EXTENSION)

    execute {
        val (calls, fields) = redirectHiddenMembers(
            "$EXT/OneUi;", "$EXT/Hidden;", HIDDEN_MEMBERS + HIDDEN_MEMBERS_16_17 + REFUSED_CALLS,
        )
        val constructors = redirectConstructors(
            "$EXT/OneUi;", "$EXT/Hidden;", HIDDEN_CONSTRUCTORS + HIDDEN_CONSTRUCTORS_16_17,
        )
        val rebased = rebaseSubclasses(REBASES)
        skipCastOfNull("$EXT/Hidden;")
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; rebased classes: $rebased")

        // Fold state listeners are made with a constructor only system apps may call: off a foldable there is no fold
        // state to follow, so registering the missing listener is skipped.
        val deviceState = mutableClassDefBy("Lcom/honeyspace/common/wrapper/DeviceStateManagerWrapper;")
        for ((name, callback) in listOf("registerCallback" to "p2", "unregisterCallback" to "p1")) {
            val method = deviceState.methods.firstOrNull { it.name == name }
                ?: throw PatchException("DeviceStateManagerWrapper.$name not found")
            method.addInstructionsWithLabels(
                0,
                """
                    if-nez $callback, :callback
                    return-void
                """,
                ExternalLabel("callback", method.implementation!!.instructions.first()),
            )
        }
        // Measured against 18.0.07.59: 947 calls, 359 field accesses, 63 constructors, 24 rebased classes.
        if (calls < 100) throw PatchException("only $calls hidden member calls found")
        if (constructors == 0 || rebased == 0) throw PatchException("hidden constructors not found")
    }
}

/**
 * What a system home screen may do and an installed app may not: launch apps with its own remote animation, bind
 * widgets without asking, keep its options in system settings, use One UI's status bar service.
 */
private val systemPrivilegesPatch = bytecodePatch(
    description = "Uses Android's own launch animation, asks for the widget permission and keeps its options locally.",
) {
    extendWith(SAMSUNG_LAUNCHER_EXTENSION)

    execute {
        val services = redirectSystemServices("$EXT/Services;")
        val settings = redirectCalls(SETTINGS_CALLS)
        val widgets = redirectCalls(WIDGET_BINDS)
        val windows = redirectCalls(WINDOW_CALLS)
        val reflection = redirectCalls(REFLECTION_CALLS)
        log.info("reflection lookups: $reflection")

        // The Kotlin reflection helper asks Reflect.invoke first (a drag event's display, so icons can be moved).
        val invoke = mutableClassDefBy("Lcom/honeyspace/common/reflection/ReflectionUtilsKt;").methods.firstOrNull {
            it.name == "invokeReflection" && it.parameterTypes.size == 3
        } ?: throw PatchException("ReflectionUtilsKt.invokeReflection not found")
        invoke.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p1, p2 }, $EXT/Reflect;->invoke(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                sget-object v1, $EXT/Reflect;->NONE:Ljava/lang/Object;
                if-eq v0, v1, :own
                return-object v0
            """,
            ExternalLabel("own", invoke.implementation!!.instructions.first()),
        )
        log.info("system services: $services, settings calls: $settings, widget binds: $widgets, windows: $windows")
        // Measured against 18.0.07.59: 168 settings calls.
        if (settings < 50) throw PatchException("only $settings settings calls found")
        if (widgets == 0) throw PatchException("no widget binds found")

        // App launches: without the remote animation permission, Android's own launch (the path One UI Home itself
        // takes in DeX).
        val launchOptions = mutableClassDefBy("Lcom/honeyspace/transition/ShellTransitionManager;").methods.firstOrNull {
            it.name == "getActivityLaunchOptions" && it.parameterTypes.size == 2 &&
                it.returnType == "Lcom/honeyspace/transition/utils/ActivityOptionsWrapper;"
        } ?: throw PatchException("ShellTransitionManager.getActivityLaunchOptions not found")
        launchOptions.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXT/Launch;->remoteAnimations()Z
                move-result v0
                if-nez v0, :remote
                invoke-static { }, $ACTIVITY_OPTIONS->makeBasic()$ACTIVITY_OPTIONS
                move-result-object v0
                new-instance v1, Lcom/honeyspace/transition/utils/ActivityOptionsWrapper;
                invoke-direct { v1, v0 }, Lcom/honeyspace/transition/utils/ActivityOptionsWrapper;-><init>($ACTIVITY_OPTIONS)V
                return-object v1
            """,
            ExternalLabel("remote", launchOptions.implementation!!.instructions.first()),
        )

        // Suggested apps come from Android's app prediction service, which only answers a caller with usage access
        // (PACKAGE_USAGE_STATS); without it there are no suggestions instead of a crash.
        val predictions = mutableClassDefBy("Lcom/honeyspace/core/repository/PredictionDataSourceImpl;").methods
            .firstOrNull { it.name == "initPredicationData" && it.parameterTypes.size == 1 }
            ?: throw PatchException("PredictionDataSourceImpl.initPredicationData not found")
        predictions.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXT/Launch;->predictions()Z
                move-result v0
                if-nez v0, :allowed
                return-void
            """,
            ExternalLabel("allowed", predictions.implementation!!.instructions.first()),
        )

        // The home screen's own close/recents transitions are registered with the shell as remote transitions, which
        // likewise needs the permission: without it they are not registered (Android's own animations play).
        val shell = mutableClassDefBy("Lcom/honeyspace/transition/ShellTransitionManager;")
        for (name in listOf(
            "registerRemoteTransitions", "unregisterRemoteTransitions", "setAppCloseRemoteTransition",
            "setRecentCloseRemoteTransition", "unregisterRecentsRemoteTransitions",
        )) {
            val method = shell.methods.firstOrNull { it.name == name && it.returnType == "V" }
                ?: throw PatchException("ShellTransitionManager.$name not found")
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXT/Launch;->remoteAnimations()Z
                    move-result v0
                    if-nez v0, :remote
                    return-void
                """,
                ExternalLabel("remote", method.implementation!!.instructions.first()),
            )
        }

        // While an icon is dragged One UI hides the status bar; elsewhere Android then shows its "Viewing full screen"
        // hint, a full-screen window that takes the drop, and the icon cannot be moved. The bars stay shown here.
        val systemUi = mutableClassDefBy("Lcom/honeyspace/ui/common/util/SystemUIControlUtils;")
        val update = systemUi.methods.firstOrNull {
            it.name == "updateSystemUI" && it.parameterTypes.map { t -> t.toString() } == listOf("Landroid/view/Window;", "Z", "I", "Z")
        } ?: throw PatchException("SystemUIControlUtils.updateSystemUI not found")
        update.addInstructionsWithLabels(
            0,
            """
                if-nez p2, :show
                return-void
            """,
            ExternalLabel("show", update.implementation!!.instructions.first()),
        )
        val hideExceptions = systemUi.methods.firstOrNull {
            it.name.startsWith("updateSystemUI\$lambda") && it.parameterTypes.map { t -> t.toString() } ==
                listOf("I", "Landroid/view/WindowInsetsController;", "I")
        } ?: throw PatchException("SystemUIControlUtils.updateSystemUI lambda not found")
        hideExceptions.addInstructions(0, "const/4 p2, 0x0")

        // Everything else that asks for a remote animation (widget and recents launches). The method has no spare
        // register, so it is rebuilt with one.
        val compat = mutableClassDefBy("Lcom/android/systemui/shared/launcher/ActivityOptionsCompat;")
        val remote = compat.methods.firstOrNull {
            it.name == "makeRemoteAnimation" && it.returnType == ACTIVITY_OPTIONS && it.parameterTypes.size == 2
        } ?: throw PatchException("ActivityOptionsCompat.makeRemoteAnimation not found")
        val parameters = remote.parameterTypes.map { it.toString() }
        compat.methods.remove(remote)
        compat.methods.add(
            ImmutableMethod(
                remote.definingClass, remote.name, parameters.map { ImmutableMethodParameter(it, null, null) },
                remote.returnType, remote.accessFlags, null, null, MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { }, $EXT/Launch;->remoteAnimations()Z
                        move-result v0
                        if-eqz v0, :basic
                        invoke-static { p0, p1 }, $ACTIVITY_OPTIONS->makeRemoteAnimation(${parameters.joinToString("")})$ACTIVITY_OPTIONS
                        move-result-object v0
                        return-object v0
                        :basic
                        invoke-static { }, $ACTIVITY_OPTIONS->makeBasic()$ACTIVITY_OPTIONS
                        move-result-object v0
                        return-object v0
                    """,
                )
            },
        )
    }
}

/**
 * Off One UI the home screen dies at once on SemFloatingFeature (a One UI class). The extension carries stand-ins for
 * the One UI classes it calls; on a Samsung phone the framework's own classes load first, so they never run there.
 * Recents and navigation gestures stay the phone's own: Android gives them only to the system's home app.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes One UI Home usable as a home screen on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_LAUNCHER)
    dependsOn(resourcesPatch, oneUiMembersPatch, hiddenApisPatch, systemPrivilegesPatch)
    extendWith(SAMSUNG_LAUNCHER_EXTENSION)
}
