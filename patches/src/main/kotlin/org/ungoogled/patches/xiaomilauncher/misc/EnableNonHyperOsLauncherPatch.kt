package org.ungoogled.patches.xiaomilauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.coloroslauncher.misc.rebaseSubclasses
import org.ungoogled.patches.coloroslauncher.misc.redirectConstructors
import org.ungoogled.patches.coloroslauncher.misc.redirectHiddenMembers
import org.ungoogled.patches.coloroslauncher.misc.skipCastOfNull
import org.ungoogled.patches.coloroslauncher.misc.substituteSuperConstructors
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.xiaomilauncher.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.xiaomilauncher.HIDDEN_CONSTRUCTORS_16_17
import org.ungoogled.patches.xiaomilauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.xiaomilauncher.HIDDEN_MEMBERS_16_17
import org.ungoogled.patches.xiaomilauncher.HIDDEN_SUPERCLASSES
import org.ungoogled.patches.xiaomilauncher.SUPER_CONSTRUCTORS
import org.ungoogled.patches.xiaomilauncher.XiaomiLauncherConstants.COMPATIBILITY_XIAOMI_LAUNCHER

internal const val XIAOMI_LAUNCHER_EXTENSION = "extensions/xiaomilauncher.mpe"

private const val EXT = "Lorg/ungoogled/xiaomilauncher"

private val log = java.util.logging.Logger.getLogger("XiaomiLauncher")

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

/** Translucent system bars, which HyperOS shows transparent and Android with a grey scrim (see Launch.addFlags). */
private val BAR_CALLS = mapOf(
    "Landroid/view/Window;->addFlags(I)V" to "$EXT/Launch;->addFlags(Landroid/view/Window;I)V",
)

/** Widget binds: refused ones open Android's widget permission dialog (see Widgets, from One UI Home's port). */
private val WIDGET_BINDS = listOf(
    "(ILandroid/content/ComponentName;)Z",
    "(ILandroid/content/ComponentName;Landroid/os/Bundle;)Z",
    "(ILandroid/os/UserHandle;Landroid/content/ComponentName;Landroid/os/Bundle;)Z",
).associate { shape ->
    "Landroid/appwidget/AppWidgetManager;->bindAppWidgetIdIfAllowed$shape" to
        "$EXT/Widgets;->bind(Landroid/appwidget/AppWidgetManager;${shape.substring(1)}"
}

/** Uninstalls, which need DELETE_PACKAGES: through Android's own uninstall dialog (see Launch.deletePackage). */
private val UNINSTALL_CALLS = mapOf(
    "Landroid/content/pm/PackageManager;->deletePackage(Ljava/lang/String;Landroid/content/pm/IPackageDeleteObserver;I)V" to
        "$EXT/Launch;->deletePackage(Landroid/content/pm/PackageManager;Ljava/lang/String;Ljava/lang/Object;I)V",
    "Landroid/content/pm/PackageManager;->deletePackageAsUser(Ljava/lang/String;Landroid/content/pm/IPackageDeleteObserver;II)V" to
        "$EXT/Launch;->deletePackageAsUser(Landroid/content/pm/PackageManager;Ljava/lang/String;Ljava/lang/Object;II)V",
)

/** Calls an installed app may make but that fail for it (see Calls). */
private val GUARDED_CALLS = mapOf(
    "Landroid/content/ContentResolver;->registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;)V" to
        "$EXT/Calls;->registerContentObserver(Landroid/content/ContentResolver;Landroid/net/Uri;ZLandroid/database/ContentObserver;)V",
    "Landroid/app/ActivityManager;->getCurrentUser()I" to "$EXT/Calls;->getCurrentUser()I",
)

/**
 * The APK carries a compile-time copy of HyperOS's miui.process.ProcessManager with only two of its methods (on HyperOS
 * the framework's full class loads first); the launcher calls these others too, a NoSuchMethodError off HyperOS (the
 * first at start, from the recents' AppObserver). Through Members they answer "absent": no foreground listener, no
 * locked apps, nothing killed.
 */
private val PROCESS_MANAGER_GAPS = setOf(
    "Lmiui/process/ProcessManager;->getLockedApplication(I)Ljava/util/List;",
    "Lmiui/process/ProcessManager;->isLockedApplication(Ljava/lang/String;I)Z",
    "Lmiui/process/ProcessManager;->kill(Lmiui/process/ProcessConfig;)Z",
    "Lmiui/process/ProcessManager;->registerForegroundInfoListener(Lmiui/process/IForegroundInfoListener;)V",
    "Lmiui/process/ProcessManager;->updateApplicationLockedState(Ljava/lang/String;IZ)V",
)

/**
 * Open Android calls that only the system may make (a SecurityException for an installed app, which killed the
 * launcher's background thread): through Members, which answers a refused call with "absent". The status bar
 * controller disables the status bar's expansion during home gestures.
 */
private val SYSTEM_ONLY_CALLS = setOf(
    "Landroid/app/StatusBarManager;->disable(I)V",
)

/**
 * An Android class the launcher carries a copy of that Android 16 and 17 have, with a constructor only the system may
 * call (the framework's class wins): its subclass is rebased onto Object as well (the recents model made one at start,
 * which stopped the launcher on Android 17).
 */
private val SUPERCLASSES_16_17 = mapOf(
    "Landroid/window/TaskSnapshotListener;" to ("Ljava/lang/Object;" to emptyList<String>()),
)

/**
 * The HyperOS launcher is a system app built against the full Android framework; an installed app may not link to its
 * hidden parts. Each hidden member it uses goes through Members (used where Android allows it, "absent" elsewhere), each
 * `new` of a class an app may not construct through Members.construct, and subclasses of such classes are rebased.
 * The lists are generated (.github/scripts/coloros-launcher/hidden.py), the helpers are the OnePlus System Launcher
 * port's.
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(XIAOMI_LAUNCHER_EXTENSION)

    execute {
        val (calls, fields) = redirectHiddenMembers(
            "$EXT/Members;", "$EXT/Hidden;",
            HIDDEN_MEMBERS + HIDDEN_MEMBERS_16_17 + PROCESS_MANAGER_GAPS + SYSTEM_ONLY_CALLS,
        )
        val constructors = redirectConstructors(
            "$EXT/Members;", "$EXT/Hidden;", HIDDEN_CONSTRUCTORS + HIDDEN_CONSTRUCTORS_16_17,
        )
        val superCalls = substituteSuperConstructors(SUPER_CONSTRUCTORS)
        val rebased = rebaseSubclasses(HIDDEN_SUPERCLASSES + SUPERCLASSES_16_17)
        val casts = nullCastsToRebasedBases(HIDDEN_SUPERCLASSES.keys + SUPERCLASSES_16_17.keys)
        skipCastOfNull("$EXT/Hidden;")
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; super calls: $superCalls; rebased classes: $rebased; casts to their old bases: $casts")
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
 * receivers without saying who may send, open system-only windows, ask the app prediction service, bind widgets and
 * uninstall apps without asking, animate launches remotely.
 */
private val systemPrivilegesPatch = bytecodePatch(
    description = "Keeps its options locally and uses Android's own launch animations, widget and uninstall dialogs.",
) {
    extendWith(XIAOMI_LAUNCHER_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers("$EXT/Receivers;")
        val settings = redirectCalls(SETTINGS_CALLS)
        val windows = redirectCalls(WINDOW_CALLS)
        val reflection = redirectCalls(REFLECTION_CALLS)
        val guarded = redirectCalls(GUARDED_CALLS)
        val bars = redirectCalls(BAR_CALLS)
        val uninstalls = redirectCalls(UNINSTALL_CALLS)
        val binds = redirectCalls(WIDGET_BINDS)
        log.info("widget binds: $binds")
        if (binds == 0) throw PatchException("no AppWidgetManager.bindAppWidgetIdIfAllowed calls found")
        log.info("uninstall calls: $uninstalls")
        if (uninstalls == 0) throw PatchException("no PackageManager.deletePackage calls found")
        log.info("window flag calls: $bars")
        if (bars == 0) throw PatchException("no Window.addFlags calls found")
        log.info("receivers: $receivers, settings calls: $settings, windows: $windows, reflection: $reflection, guarded: $guarded")
        if (settings < 20) throw PatchException("only $settings settings calls found")
        if (guarded < 2) throw PatchException("only $guarded content observer / user calls found")

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

        // Launches: HyperOS's ActivityOptionsCompat.makeRemoteAnimation(adapter, transition) makes remote-animation
        // options by reflection, which only a home app holding CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS (privileged) may
        // use; it answered null and every launch stopped on it. Without the permission it gives Android's own options.
        // (The one-argument shape calls ActivityOptions.makeRemoteAnimation directly: Members/Fallbacks answer it.)
        val compat = mutableClassDefByOrNull("Lcom/android/systemui/shared/recents/system/ActivityOptionsCompat;")
            ?: throw PatchException("ActivityOptionsCompat not found")
        val makers = compat.methods.filter {
            it.name == "makeRemoteAnimation" && it.returnType == "Landroid/app/ActivityOptions;" && it.parameterTypes.size == 2
        }
        if (makers.size != 1) throw PatchException("ActivityOptionsCompat.makeRemoteAnimation(adapter, transition): found ${makers.size}")
        for (m in makers) {
            if (m.implementation!!.registerCount - m.parameterTypes.size < 1) throw PatchException("makeRemoteAnimation has no spare register")
            m.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXT/Launch;->basicOptions()Landroid/app/ActivityOptions;
                    move-result-object v0
                    if-eqz v0, :remote
                    return-object v0
                """,
                ExternalLabel("remote", m.implementation!!.instructions.first()),
            )
        }

        // Back to Home after a launch: the transition helper finishes the (never registered) remote transition with a
        // new WindowContainerTransaction, a class only the system may make (null here), and marks it "to home" by
        // reflection on it: a NullPointerException that killed the launcher, after which Android forgets the user's
        // Home choice. A missing transaction is skipped.
        val transaction = "Landroid/window/WindowContainerTransaction;"
        val markers = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.name == "setWillFinishToHome" && m.returnType == "V" &&
                    m.parameterTypes.map { it.toString() } == listOf(transaction, "Z") && m.implementation != null
                ) markers += c.type to m
            }
        }
        val (markerType, marker) = markers.singleOrNull()
            ?: throw PatchException("expected one setWillFinishToHome(WindowContainerTransaction, boolean), found ${markers.size}")
        val mutableMarker = mutableMethod(markerType, marker)
        mutableMarker.addInstructionsWithLabels(
            0,
            """
                if-nez p1, :go
                return-void
            """,
            ExternalLabel("go", mutableMarker.implementation!!.instructions.first()),
        )
    }
}

/**
 * Offline unless asked: the launcher's app search (appfinder) starts Firebase (Installations, Crashlytics) at start and
 * then reports to Xiaomi (privacy.api.intl.miui.com/collect/privacy/agree/v1) that the user agreed to Xiaomi's terms,
 * with the Firebase id and HyperOS's terms time or, off HyperOS, the current time. Nobody agreed to anything here:
 * Firebase is never started (its crash reports check for that), so neither call is made.
 */
private val offlinePatch = bytecodePatch(
    description = "Keeps the launcher offline: no Firebase, no consent report to Xiaomi.",
) {
    execute {
        fun strings(m: Method) = m.implementation?.instructions
            ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
        fun calls(m: Method) = m.implementation?.instructions
            ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference) }.orEmpty()

        val starts = mutableListOf<Pair<String, Method>>()
        val reports = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.returnType != "V" || m.parameterTypes.isNotEmpty()) continue
                val called = calls(m)
                if (called.any { it.definingClass == "Lcom/google/firebase/FirebaseApp;" && it.name == "initializeApp" } &&
                    "Start to init firebase" in strings(m)
                ) starts += c.type to m
                // The consent report: asks Firebase Installations for the id, once (a "reported" flag).
                if (called.any { it.definingClass == "Lcom/google/firebase/installations/FirebaseInstallations;" && it.name == "getId" } &&
                    m.accessFlags and AccessFlags.STATIC.value != 0
                ) reports += c.type to m
            }
        }
        // Measured against 7.50.06.2372: FirebaseManager.initFirebase and PrivacyManager.reportFirebasePrivacy.
        val (startType, start) = starts.singleOrNull() ?: throw PatchException("expected one Firebase start, found ${starts.size}")
        val (reportType, report) = reports.singleOrNull() ?: throw PatchException("expected one consent report, found ${reports.size}")
        mutableMethod(startType, start).addInstructions(0, "return-void")
        mutableMethod(reportType, report).addInstructions(0, "return-void")
        log.info("Firebase start: $startType->${start.name}; consent report: $reportType->${report.name}")
    }
}

/**
 * Android's uninstall dialog (see Launch.deletePackage) only opens for apps that ask for REQUEST_DELETE_PACKAGES; the
 * widget permission dialog is asked for from an activity of the extension's (see Widgets).
 */
private val manifestPatch = resourcePatch(
    description = "Lets the launcher open Android's uninstall and widget permission dialogs.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0)
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", "org.ungoogled.xiaomilauncher.WidgetPermissionActivity")
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:excludeFromRecents", "true")
            activity.setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
            application.appendChild(activity)
            val root = manifest.documentElement
            val permissions = manifest.getElementsByTagName("uses-permission")
            val name = "android.permission.REQUEST_DELETE_PACKAGES"
            if ((0 until permissions.length).none { (permissions.item(it) as org.w3c.dom.Element).getAttribute("android:name") == name }) {
                val permission = manifest.createElement("uses-permission")
                permission.setAttribute("android:name", name)
                root.insertBefore(permission, permissions.item(0))
            }
        }
    }
}

/**
 * Off HyperOS the launcher dies at once on HyperOS's framework classes (miui.os.Build first) and then on Android's hidden
 * API, which only a system app may use. The extension carries stand-ins for the HyperOS classes it calls (generated by
 * .github/scripts/xiaomi-launcher; on a Xiaomi phone the framework's own classes load first). Recents and navigation
 * gestures stay the phone's own: Android gives them only to the system's home app.
 */
@Suppress("unused")
val enableNonHyperOsLauncherPatch = bytecodePatch(
    name = "Enable non-HyperOS ROM support",
    description = "Makes the HyperOS launcher usable as a home screen on non-Xiaomi ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_XIAOMI_LAUNCHER)
    dependsOn(hiddenApisPatch, systemPrivilegesPatch, manifestPatch, offlinePatch)
    extendWith(XIAOMI_LAUNCHER_EXTENSION)
}
