package org.ungoogled.patches.samsungroutines.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.ungoogled.patches.samsungroutines.SamsungRoutinesConstants.COMPATIBILITY_SAMSUNG_ROUTINES
import org.w3c.dom.Element

internal const val SAMSUNG_ROUTINES_EXTENSION = "extensions/samsungroutines.mpe"

private const val LOG = "SamsungRoutines"

/**
 * On One UI Modes and Routines runs inside SystemUI's user id (android.uid.systemui), which only a platform-signed app
 * can share: elsewhere the install fails. Its launcher icon is also off until switched on in One UI's settings, and
 * there is no other way in.
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            if (!root.hasAttribute("android:sharedUserId")) throw PatchException("the manifest has no sharedUserId")
            root.removeAttribute("android:sharedUserId")
            root.removeAttribute("coreApp")

            val aliases = manifest.getElementsByTagName("activity-alias")
            var found = false
            for (i in 0 until aliases.length) {
                val alias = aliases.item(i) as Element
                if (!alias.getAttribute("android:name").endsWith(".ui.main.RoutineLaunchActivityDummy")) continue
                alias.setAttribute("android:enabled", "true")
                found = true
            }
            if (!found) throw PatchException("no RoutineLaunchActivityDummy launcher alias in the manifest")

            // SystemUI may run "system exempted" foreground services; any other app needs an exact-alarm grant for
            // them. The loading service becomes "special use" (see Foreground).
            var exempted = 0
            val services = manifest.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val service = services.item(i) as Element
                if (service.getAttribute("android:foregroundServiceType") != "systemExempted") continue
                service.setAttribute("android:foregroundServiceType", "specialUse")
                service.appendChild(
                    manifest.createElement("property").apply {
                        setAttribute("android:name", "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
                        setAttribute("android:value", "Loads the conditions and actions routines can use")
                    },
                )
                exempted++
            }
            if (exempted == 0) throw PatchException("no systemExempted foreground service in the manifest")
            // The engine host keeps the routine engine running (see EngineHost) and comes back after a reboot.
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("service").apply {
                    setAttribute("android:name", "org.ungoogled.samsungroutines.EngineHost")
                    setAttribute("android:exported", "false")
                    setAttribute("android:foregroundServiceType", "specialUse")
                    appendChild(
                        manifest.createElement("property").apply {
                            setAttribute("android:name", "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
                            setAttribute("android:value", "Keeps the routine engine running to watch conditions and run routines")
                        },
                    )
                },
            )
            application.appendChild(
                manifest.createElement("receiver").apply {
                    setAttribute("android:name", "org.ungoogled.samsungroutines.EngineHost\$Boot")
                    setAttribute("android:exported", "false")
                    appendChild(
                        manifest.createElement("intent-filter").apply {
                            for (action in listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED")) {
                                appendChild(manifest.createElement("action").apply { setAttribute("android:name", action) })
                            }
                        },
                    )
                },
            )

            val declared = manifest.getElementsByTagName("uses-permission").let { list ->
                (0 until list.length).map { (list.item(it) as Element).getAttribute("android:name") }.toSet()
            }
            // Time conditions set alarm clocks, which SystemUI may; any other app needs an exact-alarm permission.
            for (permission in listOf(
                "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.USE_EXACT_ALARM",
            )) {
                if (permission in declared) continue
                root.appendChild(manifest.createElement("uses-permission").apply { setAttribute("android:name", permission) })
            }
        }
    }
}

/** The loading service's startForeground asks for "special use" instead of "system exempted" (see manifestPatch). */
private val foregroundPatch = bytecodePatch(
    description = "Starts the loading service as a special-use foreground service.",
) {
    extendWith(SAMSUNG_ROUTINES_EXTENSION)

    execute {
        val moved = redirectCalls(
            "Lorg/ungoogled/samsungroutines/Foreground;",
            mapOf("Landroid/app/Service;" to setOf("startForeground(ILandroid/app/Notification;I)V")),
        )
        if (moved == 0) throw PatchException("no typed startForeground call found")
    }
}

/**
 * On One UI the routine engine runs in SystemUI's always-running process. Elsewhere Android froze the app in the
 * background and refused its service starts, so condition changes never reached the engine: the app starts a
 * foreground service that keeps it running (see EngineHost).
 */
private val engineHostPatch = bytecodePatch(
    description = "Keeps the routine engine running in a foreground service.",
) {
    extendWith(SAMSUNG_ROUTINES_EXTENSION)

    execute {
        ApplicationOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, Lorg/ungoogled/samsungroutines/EngineHost;->start(Landroid/content/Context;)V",
        )
    }
}

/**
 * One UI opens Modes and Routines from Settings and Quick settings and keeps its launcher icon off unless asked; the app
 * turns the icon off again at start-up. Elsewhere the icon is the only way in, so it always stays on.
 */
private val launcherIconPatch = bytecodePatch(
    description = "Keeps the launcher icon on.",
) {
    execute {
        LauncherIconToggleFingerprint.method.addInstructions(0, "const/4 p1, 0x1")
    }
}

private const val SETTINGS_ACCESS = "Lorg/ungoogled/samsungroutines/SettingsAccess;"

/** Settings.Secure / System / Global reads and writes, by shape: "name(params)return" to the wrapper's name suffix. */
private val SETTINGS_SHAPES = mapOf(
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I" to "GetInt",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I" to "GetInt",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)F" to "GetFloat",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;)F" to "GetFloat",
    "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;" to "GetString",
    "putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z" to "PutInt",
    "putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z" to "PutString",
)

/**
 * As SystemUI the app may read and write any setting; elsewhere a refused write (WRITE_SECURE_SETTINGS, "Modify system
 * settings") or read (Android's hidden settings) threw SecurityException and crashed the action screens. Every such
 * call goes through SettingsAccess, which answers a refusal instead.
 */
private val settingsPatch = bytecodePatch(
    description = "Answers settings reads and writes the phone refuses.",
) {
    extendWith(SAMSUNG_ROUTINES_EXTENSION)

    execute {
        val calls = buildMap {
            for (table in listOf("Secure", "System", "Global")) {
                for ((shape, suffix) in SETTINGS_SHAPES) {
                    val name = table.lowercase() + suffix
                    put("Landroid/provider/Settings\$$table;->$shape", "$SETTINGS_ACCESS->$name${shape.substring(shape.indexOf('('))}")
                }
            }
        }
        val moved = redirectCalls(calls)
        java.util.logging.Logger.getLogger(LOG).info("Settings calls: $moved")
        // Measured against 5.1.03.1: 246 calls.
        if (moved < 100) throw PatchException("only $moved Settings calls found")
    }
}

/**
 * The routine conditions hand One UI's event delegation service an IntentFilter and a PendingIntent to fire. The
 * extension's stand-in for that service (EventDelegation) has to make the PendingIntent again after a restart, so the
 * app's PendingIntents are made through PendingIntents, which remembers how.
 */
private val pendingIntentsPatch = bytecodePatch(
    description = "Remembers how the app's PendingIntents are made.",
) {
    extendWith(SAMSUNG_ROUTINES_EXTENSION)

    execute {
        val shape = "(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;"
        val moved = redirectCalls(
            "Lorg/ungoogled/samsungroutines/PendingIntents;",
            mapOf("Landroid/app/PendingIntent;" to setOf("getService$shape", "getForegroundService$shape", "getBroadcast$shape")),
        )
        // Measured against 5.1.03.1: 89 calls.
        if (moved < 40) throw PatchException("only $moved PendingIntent calls found")
    }
}

/**
 * The charging and battery conditions read One UI's own battery broadcast ("SEC_BATTERY_EVENT") and its plug type
 * extra; elsewhere there is none and the condition never held. Android's battery broadcast carries the same extras
 * under its own names.
 */
private val batteryEventPatch = bytecodePatch(
    description = "Reads the battery state from Android's battery broadcast.",
) {
    execute {
        val rewritten = rewriteStringLiterals { s ->
            when (s) {
                "com.samsung.server.BatteryService.action.SEC_BATTERY_EVENT" -> "android.intent.action.BATTERY_CHANGED"
                "sec_plug_type" -> "plugged"
                else -> null
            }
        }
        // Measured against 5.1.03.1: 3 actions and 3 extras.
        if (rewritten < 4) throw PatchException("only $rewritten battery literals found")
    }
}

/**
 * A routine's "Open an app" starts the activity from the engine in the background, which Android blocks unless the
 * user allowed "Display over other apps" (SystemUI needs no such thing): the app's Context.startActivity calls go
 * through Access, which asks for it from the engine.
 */
private val activityStartsPatch = bytecodePatch(
    description = "Asks for Display over other apps when a routine opens an app.",
) {
    extendWith(SAMSUNG_ROUTINES_EXTENSION)

    execute {
        val moved = redirectCalls(
            "Lorg/ungoogled/samsungroutines/Access;",
            mapOf(
                "Landroid/content/Context;" to setOf(
                    "startActivity(Landroid/content/Intent;)V",
                    "startActivity(Landroid/content/Intent;Landroid/os/Bundle;)V",
                ),
            ),
        )
        // Measured against 5.1.03.1: 187 calls.
        if (moved < 50) throw PatchException("only $moved startActivity calls found")
    }
}

/** One UI grants its own apps notifications; elsewhere the main screen asks for them (see Access.onMainScreen). */
private val notificationsPatch = bytecodePatch(
    description = "Asks for the notification permission on the main screen.",
) {
    extendWith(SAMSUNG_ROUTINES_EXTENSION)

    execute {
        MainScreenOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, Lorg/ungoogled/samsungroutines/Access;->onMainScreen(Landroid/app/Activity;)V",
        )
    }
}

/** Do not disturb rule calls without notification policy access answer "no rules" instead of throwing (see Zen). */
private val zenPatch = bytecodePatch(
    description = "Answers Do not disturb rule calls the user has not allowed yet.",
) {
    extendWith(SAMSUNG_ROUTINES_EXTENSION)

    execute {
        val moved = redirectCalls(
            "Lorg/ungoogled/samsungroutines/Zen;",
            mapOf(
                "Landroid/app/NotificationManager;" to setOf(
                    "getAutomaticZenRules()Ljava/util/Map;",
                    "getAutomaticZenRule(Ljava/lang/String;)Landroid/app/AutomaticZenRule;",
                    "addAutomaticZenRule(Landroid/app/AutomaticZenRule;)Ljava/lang/String;",
                    "updateAutomaticZenRule(Ljava/lang/String;Landroid/app/AutomaticZenRule;)Z",
                    "removeAutomaticZenRule(Ljava/lang/String;)Z",
                    "setAutomaticZenRuleState(Ljava/lang/String;Landroid/service/notification/Condition;)V",
                ),
            ),
        )
        // Measured against 5.1.03.1: 18 calls.
        if (moved < 9) throw PatchException("only $moved Do not disturb rule calls found")
    }
}

/** Sends the app's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_ROUTINES_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungroutines/OneUi;", "Lorg/ungoogled/samsungroutines/Sem;")
        java.util.logging.Logger.getLogger(LOG).info("One UI members: $calls calls, $fields fields")
        // Measured against 5.1.03.1: 197 calls and 20 field accesses.
        if (calls < 50) throw PatchException("only $calls One UI calls and $fields field accesses found")
    }
}

/** One UI's own system services the app casts without a check ("sem_statusbar", "sec_location", ...) get stand-ins. */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_ROUTINES_EXTENSION)

    execute {
        val moved = redirectSystemServices("Lorg/ungoogled/samsungroutines/Services;")
        java.util.logging.Logger.getLogger(LOG).info("System service calls: $moved")
        // Measured against 5.1.03.1: 457 calls.
        if (moved < 100) throw PatchException("only $moved getSystemService calls found")
    }
}

/** Some icons are SPR files, Samsung's vector format that only One UI can draw: they become vector drawables. */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        java.util.logging.Logger.getLogger(LOG).info("SPR drawables: $converted")
        // Measured against 5.1.03.1: 14 drawables.
        if (converted < 7) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Off One UI Modes and Routines cannot install (SystemUI's user id), then crashes at start on SystemUI's privileges
 * (a "system exempted" foreground service, Do not disturb rules, settings writes), and its engine never sees a
 * condition change: One UI's event delegation service, which watches broadcasts for it, does not exist, and Android
 * freezes the app in the background. The extension stands in for the event delegation service in the app's own
 * engine process, kept running by a foreground service, and carries stand-ins for the One UI classes it calls.
 * Actions provided by Samsung's other apps (Settings' sound mode and volume, Edge, DeX, S Pen, Bixby, ...) are not
 * offered; "App opened" needs Samsung's usage watcher and stays inert.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Modes and Routines installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_ROUTINES)
    dependsOn(
        manifestPatch, launcherIconPatch, foregroundPatch, engineHostPatch, pendingIntentsPatch, batteryEventPatch, zenPatch,
        settingsPatch, activityStartsPatch, notificationsPatch, oneUiMembersPatch, systemServicesPatch, sprDrawablesPatch,
    )
    extendWith(SAMSUNG_ROUTINES_EXTENSION)
}
