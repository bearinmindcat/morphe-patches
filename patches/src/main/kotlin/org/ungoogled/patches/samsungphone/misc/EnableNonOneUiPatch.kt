package org.ungoogled.patches.samsungphone.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.addOneUiFrameworkResources
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiFrameworkLookups
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsung.redirectToWrappers
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.ungoogled.patches.samsungphone.SamsungPhoneConstants.COMPATIBILITY_SAMSUNG_PHONE
import org.w3c.dom.Element

internal const val SAMSUNG_PHONE_EXTENSION = "extensions/samsungphone.mpe"

private const val LOG = "SamsungPhone"
private const val EXT = "Lorg/ungoogled/samsungphone"
private const val IN_CALL_SERVICE = "android.telecom.InCallService"

/**
 * Never the phone app. Android makes an app the phone app (the dialer role) only if it has an in-call service; Samsung
 * Phone's is an empty stub, because on One UI the in-call screen is a separate system app (Samsung's InCallUI). As the
 * phone app it would show no screen for any call, emergency calls included. The service is removed, so Android never
 * offers the app as the phone app, and the user's own phone app keeps showing every call. Also: no shared user id
 * (One UI's dialer uid), and Samsung's framework libraries become optional.
 */
private val manifestPatch = resourcePatch {
    execute {
        makeLibrariesOptional()
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            if (root.getAttribute("android:sharedUserId").isEmpty()) throw PatchException("no shared user id in the manifest")
            root.removeAttribute("android:sharedUserId")

            val services = manifest.getElementsByTagName("service")
            val inCall = (0 until services.length).map { services.item(it) as Element }.filter { service ->
                val actions = service.getElementsByTagName("action")
                (0 until actions.length).any { (actions.item(it) as Element).getAttribute("android:name") == IN_CALL_SERVICE }
            }
            if (inCall.isEmpty()) throw PatchException("no in-call service in the manifest")
            inCall.forEach { it.parentNode.removeChild(it) }

            // Nothing left that Telecom could bind as this app's in-call screen.
            val metas = manifest.getElementsByTagName("meta-data")
            for (i in 0 until metas.length) {
                if ((metas.item(i) as Element).getAttribute("android:name").startsWith("android.telecom.IN_CALL_SERVICE")) {
                    throw PatchException("an in-call service is still declared")
                }
            }
        }
    }
}

/** The start-up dialog asking to make Samsung Phone the default phone app (it closes the app when declined) never shows. */
private val noDefaultDialerPromptPatch = bytecodePatch(
    description = "Never asks to become the default phone app.",
) {
    execute {
        DialogManagerShowFingerprint.method.addInstructions(
            0,
            """
                const/16 v0, 0x5a
                if-ne p1, v0, :show
                return-void
                :show
                nop
            """,
        )
    }
}

/**
 * The call logs permissions are hard-restricted on Android: only the default phone app, or an app whose installer
 * allowed them (adb and root installs do; app installers, Shizuku sessions and the system installer do not), may hold
 * them, and Samsung Phone closed at start without them. Its "are all of these granted" check leaves them out (see
 * Permissions); they are still asked for. Recents says once what is missing and how to allow it (see CallHistoryNote).
 */
private val optionalCallLogPatch = bytecodePatch(
    description = "Opens without the call logs permission.",
) {
    extendWith(SAMSUNG_PHONE_EXTENSION)

    execute {
        val util = PermissionCheckFingerprint.method.definingClass
        val allGranted = mutableClassDefBy(util).methods.singleOrNull {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;", "[Ljava/lang/String;")
        } ?: throw PatchException("no all-permissions check in $util")
        allGranted.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXT/Permissions;->required([Ljava/lang/String;)[Ljava/lang/String;
                move-result-object p1
            """,
        )
        RecentsLoadFingerprint.method.addInstructions(0, "invoke-static { }, $EXT/CallHistoryNote;->onRecentsLoad()V")
    }
}

private val CONTEXT_ROOTS = setOf(
    "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/view/ContextThemeWrapper;",
    "Landroid/app/Application;", "Landroid/app/Service;",
)
private const val ACTIVITY = "Landroid/app/Activity;"
private val START = setOf(
    "startActivity(Landroid/content/Intent;)V", "startActivity(Landroid/content/Intent;Landroid/os/Bundle;)V",
)
private val START_FOR_RESULT = setOf(
    "startActivityForResult(Landroid/content/Intent;I)V", "startActivityForResult(Landroid/content/Intent;ILandroid/os/Bundle;)V",
)

/** "activity", "context" or null: what [type] is, following the app's own classes up to Android's. */
private fun BytecodePatchContext.contextKind(type: String): String? {
    var t: String? = type
    var depth = 0
    while (t != null && depth++ < 32) {
        if (t == ACTIVITY) return "activity"
        if (t in CONTEXT_ROOTS) return "context"
        t = classDefByOrNull(t)?.superclass
    }
    return null
}

/**
 * Every call the app places goes through Calls (see there): on One UI it starts CALL_PRIVILEGED intents and
 * TelecomManager.placeCall as the phone app; here Telecom gets an ordinary app's ACTION_CALL and the user's phone app
 * shows the call. An emergency number, or a call Android refuses, opens the user's phone app with the number dialled
 * in, as Android itself does. Intents the app hands to others (shortcuts, notifications) carry ACTION_CALL too.
 */
private val callsPatch = bytecodePatch(
    description = "Places calls through Android's Telecom, never as the phone app.",
) {
    extendWith(SAMSUNG_PHONE_EXTENSION)

    execute {
        DialerApplicationOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, $EXT/Calls;->init(Landroid/content/Context;)V",
        )

        // Context / Activity starts of the app's own classes as well as Android's: a call intent becomes Calls'.
        val owners = mutableSetOf<String>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                for (insn in m.implementation?.instructions ?: continue) {
                    if (insn.opcode != Opcode.INVOKE_VIRTUAL && insn.opcode != Opcode.INVOKE_VIRTUAL_RANGE) continue
                    val r = (insn as ReferenceInstruction).reference as? MethodReference ?: continue
                    if (r.name == "startActivity" || r.name == "startActivityForResult") owners += r.definingClass
                }
            }
        }
        val calls = mutableMapOf<String, String>()
        for (owner in owners) {
            val kind = contextKind(owner) ?: continue
            for (shape in START) {
                calls["$owner->$shape"] = "$EXT/Calls;->startActivity(Landroid/content/Context;${shape.substringAfter('(')}"
            }
            if (kind == "activity") {
                for (shape in START_FOR_RESULT) {
                    calls["$owner->$shape"] = "$EXT/Calls;->startActivityForResult(Landroid/app/Activity;${shape.substringAfter('(')}"
                }
            }
        }
        val starts = redirectCalls(calls)
        val telecom = redirectToWrappers("$EXT/Telecom;", setOf("Landroid/telecom/TelecomManager;"))
        val actions = rewriteStringLiterals { if (it == "android.intent.action.CALL_PRIVILEGED") "android.intent.action.CALL" else null }
        java.util.logging.Logger.getLogger(LOG).info("Activity starts: $starts, Telecom calls: $telecom, privileged call actions: $actions")
        // Measured against 16.3.52.1: 95 starts, 17 Telecom calls, 9 CALL_PRIVILEGED literals.
        if (starts < 50) throw PatchException("only $starts activity starts found")
        if (telecom < 10) throw PatchException("only $telecom Telecom calls found")
        if (actions < 5) throw PatchException("only $actions CALL_PRIVILEGED literals found")
    }
}

/** Refused phone state calls (a preloaded app's permissions on One UI) answer "nothing" instead of throwing. */
private val telephonyPatch = bytecodePatch(
    description = "Answers phone state calls the phone refuses.",
) {
    extendWith(SAMSUNG_PHONE_EXTENSION)

    execute {
        val moved = redirectToWrappers(
            "$EXT/Telephony;",
            setOf("Landroid/telephony/TelephonyManager;", "Landroid/telephony/SubscriptionManager;", "Landroid/telephony/SmsManager;"),
        )
        java.util.logging.Logger.getLogger(LOG).info("Telephony calls: $moved")
        // Measured against 16.3.52.1: 94 calls.
        if (moved < 30) throw PatchException("only $moved telephony calls found")
    }
}

/**
 * The call history comes from Samsung's "logs" provider and contacts from Android's provider read as Samsung's: both
 * are fitted to Android's (see ProviderCalls). Blocking goes to Samsung's call settings provider, which only One UI
 * has: it opens Android's blocked numbers screen instead. Missing providers are not watched or notified (see
 * Providers).
 */
private val providersPatch = bytecodePatch(
    description = "Reads the call history and contacts from Android's providers.",
) {
    extendWith(SAMSUNG_PHONE_EXTENSION)

    execute {
        val observers = redirectToWrappers("$EXT/Providers;", setOf("Landroid/content/ContentResolver;"))
        val calls = redirectToWrappers("$EXT/ProviderCalls;", setOf("Landroid/content/ContentResolver;"))
        java.util.logging.Logger.getLogger(LOG).info("Provider calls: $calls, observers: $observers")
        // Measured against 16.3.52.1: 440 calls, 51 observers and notifications.
        if (calls < 200 || observers < 10) throw PatchException("only $calls provider calls found")
    }
}

/** Context.getDisplay() throws for contexts without a display: Displays answers the default. */
private val displayPatch = bytecodePatch(
    description = "Answers the default display for contexts without one.",
) {
    extendWith(SAMSUNG_PHONE_EXTENSION)

    execute {
        val moved = redirectCalls("$EXT/Displays;", mapOf("Landroid/content/Context;" to setOf("getDisplay()Landroid/view/Display;")))
        if (moved == 0) throw PatchException("no getDisplay calls found")
    }
}

private val SETTINGS_SHAPES = mapOf(
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I" to "GetInt",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I" to "GetInt",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)F" to "GetFloat",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;)F" to "GetFloat",
    "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;" to "GetString",
    "putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z" to "PutInt",
    "putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z" to "PutString",
)

/** A refused settings write or read threw SecurityException: SettingsAccess answers it. */
private val settingsPatch = bytecodePatch(
    description = "Answers settings reads and writes the phone refuses.",
) {
    extendWith(SAMSUNG_PHONE_EXTENSION)

    execute {
        val calls = buildMap {
            for (table in listOf("Secure", "System", "Global")) {
                for ((shape, suffix) in SETTINGS_SHAPES) {
                    put("Landroid/provider/Settings\$$table;->$shape", "$EXT/SettingsAccess;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}")
                }
            }
        }
        val moved = redirectCalls(calls)
        java.util.logging.Logger.getLogger(LOG).info("Settings calls: $moved")
        // Measured against 16.3.52.1: 239 calls.
        if (moved < 80) throw PatchException("only $moved Settings calls found")
    }
}

/** One UI's framework colour the app looks up by name in dark mode, added as an app colour (see OneUi.getIdentifier). */
private val oneUiFrameworkResourcesPatch = resourcePatch {
    execute {
        if (addOneUiFrameworkResources() == 0) throw PatchException("no One UI framework resource added")
    }
}

/**
 * Sends the app's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers), and its
 * resource lookups by name: in dark mode it colours its cards with One UI's framework colour (getColor(0) off One UI).
 */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    dependsOn(oneUiFrameworkResourcesPatch)
    extendWith(SAMSUNG_PHONE_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        java.util.logging.Logger.getLogger(LOG).info("One UI members: $calls calls, $fields fields")
        // Measured against 16.3.52.1: 231 calls and 13 field accesses.
        if (calls < 150) throw PatchException("only $calls One UI calls and $fields field accesses found")
        val lookups = redirectOneUiFrameworkLookups("$EXT/OneUi;")
        java.util.logging.Logger.getLogger(LOG).info("Resource lookups by name: $lookups")
        // Measured against 16.3.52.1: 35 lookups by name, 2 of them One UI's dark screen colour.
        if (lookups < 1) throw PatchException("no resource lookup by name found")
    }
}

/** One UI's own system services the app casts without a check get stand-ins. */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_PHONE_EXTENSION)

    execute {
        val moved = redirectSystemServices("$EXT/Services;") + redirectCalls(
            "$EXT/Services;",
            mapOf("Landroid/content/Context;" to setOf("getSystemService(Ljava/lang/Class;)Ljava/lang/Object;")),
        )
        java.util.logging.Logger.getLogger(LOG).info("System service calls: $moved")
        // Measured against 16.3.52.1: 233 calls.
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
        // Measured against 16.3.52.1: 35 drawables.
        if (converted < 5) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Samsung Phone as a dial pad, call history and contacts app beside the user's own phone app, which keeps showing
 * every call: Samsung's in-call screen is a separate system app (InCallUI, part of One UI's phone process), so the
 * app is never offered as the phone app. Off One UI it crashes on refused phone state and settings calls, its call
 * history and contacts fail on Android's providers, it closes without the call logs permission most installers cannot
 * allow, and as One UI's phone app it would place calls with privileges it does not have here. Emergency numbers go to
 * the user's phone app. The extension also carries stand-ins for the One UI classes it calls.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Runs Samsung Phone on non-One UI ROMs; your default phone app still handles calls.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_PHONE)
    dependsOn(
        manifestPatch, noDefaultDialerPromptPatch, callsPatch, optionalCallLogPatch, telephonyPatch, providersPatch,
        displayPatch, settingsPatch, oneUiMembersPatch, systemServicesPatch, sprDrawablesPatch,
    )
    extendWith(SAMSUNG_PHONE_EXTENSION)
}
