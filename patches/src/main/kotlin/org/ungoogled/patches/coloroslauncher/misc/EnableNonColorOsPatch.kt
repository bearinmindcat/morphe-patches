package org.ungoogled.patches.coloroslauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
import org.ungoogled.patches.coloroslauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.coloroslauncher.HIDDEN_SUPERCLASSES
import org.ungoogled.patches.coloroslauncher.SUPER_CONSTRUCTORS
import org.ungoogled.patches.samsung.redirectCalls

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
)

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
 * The System Launcher is a system app built against the full Android framework; an installed app may not link to its
 * hidden parts. Each hidden member it uses goes through Members (used where Android allows it, "absent" elsewhere),
 * each `new` of a class an app may not construct through Members.construct, and subclasses of such classes are rebased.
 * The lists are generated (.github/scripts/coloros-launcher/hidden.py).
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(COLOROS_LAUNCHER_EXTENSION)

    execute {
        val (calls, fields) = redirectHiddenMembers("$EXT/Members;", "$EXT/Hidden;", HIDDEN_MEMBERS)
        val constructors = redirectConstructors("$EXT/Members;", "$EXT/Hidden;", HIDDEN_CONSTRUCTORS)
        val superCalls = substituteSuperConstructors(SUPER_CONSTRUCTORS)
        val rebased = rebaseSubclasses(HIDDEN_SUPERCLASSES)
        skipCastOfNull("$EXT/Hidden;")
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; super calls: $superCalls; rebased classes: $rebased")
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
        val overload = create.implementation!!.instructions.mapNotNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { it.name == "onCreateView" }
                ?.let { insn.opcode to it }
        }.singleOrNull() ?: throw PatchException("LauncherAppWidgetHost.onCreateView does not call its overload once")
        val (opcode, target) = overload
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
    dependsOn(manifestPatch, hiddenApisPatch, systemPrivilegesPatch)
    extendWith(COLOROS_LAUNCHER_EXTENSION)
}
