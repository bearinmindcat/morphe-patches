package org.ungoogled.patches.coloroslauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.coloroslauncher.ColorOsLauncherConstants.COMPATIBILITY_COLOROS_LAUNCHER
import org.ungoogled.patches.coloroslauncher.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.coloroslauncher.HIDDEN_CONSTRUCTORS_15
import org.ungoogled.patches.coloroslauncher.HIDDEN_CONSTRUCTORS_16_17
import org.ungoogled.patches.coloroslauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.coloroslauncher.HIDDEN_MEMBERS_15
import org.ungoogled.patches.coloroslauncher.HIDDEN_MEMBERS_16_17
import org.ungoogled.patches.coloroslauncher.HIDDEN_SUPERCLASSES
import org.ungoogled.patches.coloroslauncher.HIDDEN_SUPERCLASSES_15
import org.ungoogled.patches.coloroslauncher.INACCESSIBLE_INTERFACES_15
import org.ungoogled.patches.coloroslauncher.SUPER_CONSTRUCTORS
import org.ungoogled.patches.coloroslauncher.SUPER_CONSTRUCTORS_15
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.shared.addDefaultsForOlderAndroid
import org.ungoogled.patches.shared.supportedVersionCheck

internal const val COLOROS_LAUNCHER_EXTENSION = "extensions/coloroslauncher.mpe"

private const val EXT = "Lorg/ungoogled/coloroslauncher"
private const val RUNTIME = "$EXT/ColorOS;"

private val log = java.util.logging.Logger.getLogger("OnePlusSystemLauncher")

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
) + listOf(
    "getBoolean(Ljava/lang/String;Ljava/lang/String;Z)Z",
    "getFloat(Ljava/lang/String;Ljava/lang/String;F)F",
    "getString(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
    "addOnPropertiesChangedListener(Ljava/lang/String;Ljava/util/concurrent/Executor;" +
        "Landroid/provider/DeviceConfig\$OnPropertiesChangedListener;)V",
).associate { shape ->
    // The listener's type is a system API the extension cannot name: Object in the helper.
    "Landroid/provider/DeviceConfig;->$shape" to
        "$EXT/Calls;->" + shape.replace("Landroid/provider/DeviceConfig\$OnPropertiesChangedListener;", "Ljava/lang/Object;")
}

/** The manifest's shared user id (oppo.uid.launcher) belongs to OPPO's signature: an installed copy goes without. */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            if (root.getAttribute("android:sharedUserId").isEmpty()) throw PatchException("no shared user id in the manifest")
            root.removeAttribute("android:sharedUserId")
            root.removeAttribute("android:sharedUserLabel")
        }
    }
}

/**
 * 15.6.24 declares Android 11 as its minimum but has some resources (colors, the home settings' styles, ...) only for
 * Android 12 and newer: they get defaults (see addDefaultsForOlderAndroid). 16.4.30 needs Android 13 anyway.
 */
private val olderAndroidResourcesPatch = resourcePatch {
    execute {
        if (packageMetadata.versionName != "15.6.24") return@execute
        val added = addDefaultsForOlderAndroid()
        log.info("resources given defaults for Android 11: $added")
        if (added < 100) throw PatchException("only $added resources given defaults for Android 11")
    }
}

/** One version's generated lists (.github/scripts/coloros-launcher/hidden.py). */
private class HiddenLists(
    val members: Set<String>,
    val constructors: Set<String>,
    val superConstructors: Map<String, String>,
    val superclasses: Map<String, Pair<String, List<String>>>,
    val inaccessible: Set<String> = emptySet(),
    // 15.6.24 copies some new objects to another register before their constructor (see redirectConstructors).
    val followMoves: Boolean = false,
)

/** 16.4.30's lists were checked against Android 15 (and 16 / 17), 15.6.24's against Android 11, 15, 16 and 17. */
private fun hiddenLists(version: String) = when (version) {
    "15.6.24" -> HiddenLists(
        HIDDEN_MEMBERS_15, HIDDEN_CONSTRUCTORS_15, SUPER_CONSTRUCTORS_15, HIDDEN_SUPERCLASSES_15,
        INACCESSIBLE_INTERFACES_15, followMoves = true,
    )
    else -> HiddenLists(
        HIDDEN_MEMBERS + HIDDEN_MEMBERS_16_17, HIDDEN_CONSTRUCTORS + HIDDEN_CONSTRUCTORS_16_17, SUPER_CONSTRUCTORS,
        HIDDEN_SUPERCLASSES,
    )
}

/**
 * App classes that implement an Android interface the phone keeps private (Android 11's
 * AnimationHandler.AnimationFrameCallback) do not load: they lose it. The calls that took them as one go through
 * Members, which needs only their methods. Returns the classes changed.
 */
private fun BytecodePatchContext.dropInterfaces(interfaces: Set<String>): Int {
    if (interfaces.isEmpty()) return 0
    val targets = mutableListOf<String>()
    classDefForEach { c ->
        if (!c.type.startsWith("Lorg/ungoogled/") && c.interfaces.any { it in interfaces }) targets += c.type
    }
    for (type in targets) mutableClassDefBy(type).interfaces.removeAll(interfaces)
    return targets.size
}

/**
 * The System Launcher is a system app built against the full Android framework; an installed app may not link to its
 * hidden parts. Each hidden member it uses goes through Members (used where Android allows it, "absent" elsewhere),
 * each `new` of a class an app may not construct through Members.construct, and subclasses of such classes are rebased.
 * The lists are generated per version (.github/scripts/coloros-launcher/hidden.py).
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(COLOROS_LAUNCHER_EXTENSION)

    execute {
        val lists = hiddenLists(packageMetadata.versionName)
        val (calls, fields) = redirectHiddenMembers("$EXT/Members;", "$EXT/Hidden;", lists.members)
        val constructors = redirectConstructors(
            "$EXT/Members;", "$EXT/Hidden;", lists.constructors, followMoves = lists.followMoves,
        )
        val superCalls = substituteSuperConstructors(lists.superConstructors)
        val rebased = rebaseSubclasses(lists.superclasses)
        val dropped = dropInterfaces(lists.inaccessible)
        if (dropped == 0 && lists.inaccessible.isNotEmpty()) throw PatchException("no classes implement ${lists.inaccessible}")
        skipCastOfNull("$EXT/Hidden;")
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; super calls: $superCalls; rebased classes: $rebased; interfaces dropped: $dropped")
        if (calls < 100) throw PatchException("only $calls hidden member calls found")
        if (constructors == 0) throw PatchException("hidden constructors not found")
        if (rebased == 0) throw PatchException("no subclasses of hidden Android classes found")
    }
}

/** What a system home screen may do and an installed app may not. */
private val systemPrivilegesPatch = bytecodePatch(
    description = "Keeps its options locally and registers widgets with Android's public calls.",
) {
    extendWith(COLOROS_LAUNCHER_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers(RUNTIME)
        val settings = redirectCalls(SETTINGS_CALLS)
        val windows = redirectCalls(WINDOW_CALLS)
        val reflection = redirectCalls(REFLECTION_CALLS)
        val guarded = redirectCalls(GUARDED_CALLS)
        log.info("receivers: $receivers, settings calls: $settings, windows: $windows, reflection: $reflection, guarded: $guarded")
        if (settings < 50) throw PatchException("only $settings settings calls found")
        if (guarded < 20) throw PatchException("only $guarded content observer / user calls found")

        // A widget view made ahead of time is registered for updates through the host's public createView (see
        // Widgets.listen): the host's onCreateView then hands that view back instead of making a new one. The method
        // only calls its private overload with no view and has no spare register, so it is rebuilt with one.
        val host = mutableClassDefBy("Lcom/android/launcher3/widget/LauncherAppWidgetHost;")
        val hostView = "Lcom/android/launcher3/widget/LauncherAppWidgetHostView;"
        val create = host.methods.firstOrNull {
            it.name == "onCreateView" && it.returnType == hostView && it.parameterTypes.map { t -> t.toString() } ==
                listOf("Landroid/content/Context;", "I", "Landroid/appwidget/AppWidgetProviderInfo;")
        } ?: throw PatchException("LauncherAppWidgetHost.onCreateView(Context, int, AppWidgetProviderInfo) not found")
        val overloads = create.implementation!!.instructions.mapNotNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { it.name == "onCreateView" }
                ?.let { insn.opcode to it }
        }
        if (overloads.isEmpty()) {
            // 15.6.24 makes its views itself and has them registered by Android's public AppWidgetHost.createView.
            val public = host.methods.any { method ->
                method.name == "createView" && method.implementation?.instructions?.any { insn ->
                    ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.definingClass == "Landroid/appwidget/AppWidgetHost;" && it.name == "createView"
                    } == true
                } == true
            }
            if (!public) throw PatchException("LauncherAppWidgetHost makes widget views neither way it is known to")
            return@execute
        }
        val (opcode, target) = overloads.singleOrNull()
            ?: throw PatchException("LauncherAppWidgetHost.onCreateView does not call its overload once")
        if (target.parameterTypes.map { it.toString() } != listOf("Landroid/content/Context;", hostView, "I")) {
            throw PatchException("unexpected onCreateView overload $target")
        }
        val parameters = create.parameterTypes.map { it.toString() }
        host.methods.remove(create)
        host.methods.add(
            ImmutableMethod(
                create.definingClass, create.name, parameters.map { ImmutableMethodParameter(it, null, null) },
                create.returnType, create.accessFlags, null, null, MutableMethodImplementation(5),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p2 }, $EXT/Widgets;->pending(I)Landroid/appwidget/AppWidgetHostView;
                        move-result-object v0
                        if-nez v0, :pending
                        const/4 v0, 0x0
                        ${opcode.name.lowercase().replace('_', '-')} { p0, p1, v0, p2 }, $target
                        move-result-object v0
                        return-object v0
                        :pending
                        check-cast v0, $hostView
                        return-object v0
                    """,
                )
            },
        )
    }
}

/** Another version of the app or too old an Android stops here with a clear message (see supportedVersionCheck). */
private val versionCheckPatch = supportedVersionCheck(COMPATIBILITY_COLOROS_LAUNCHER)

/**
 * Off ColorOS the System Launcher dies at once on com.oplus.dispatch.WorkDispatch (a ColorOS framework class). The
 * extension carries stand-ins for the ColorOS classes it uses (generated by .github/scripts/coloros-launcher): ColorOS's
 * work dispatcher runs on Android's threads, its render-thread animators on the UI thread, its partition paths point
 * nowhere; on an OPPO / OnePlus / realme phone the framework's own classes load first, so they never run there. Its
 * hidden Android API goes through Members (see hiddenApisPatch), its spring animations get their frames from the
 * Choreographer (Frames). Recents and navigation gestures stay the phone's own: Android gives them only to the system's
 * home app.
 */
@Suppress("unused")
val enableNonColorOsPatch = bytecodePatch(
    name = "Enable non-ColorOS ROM support",
    description = "Makes OnePlus System Launcher usable as a home screen on non-ColorOS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_LAUNCHER)
    dependsOn(versionCheckPatch, manifestPatch, olderAndroidResourcesPatch, hiddenApisPatch, systemPrivilegesPatch)
    extendWith(COLOROS_LAUNCHER_EXTENSION)
}
