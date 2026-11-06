package org.ungoogled.patches.coloroscontacts.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.coloros.treatRefusedComponentsAsMissing
import org.ungoogled.patches.coloroscontacts.ColorOsContactsConstants.COMPATIBILITY_COLOROS_CONTACTS
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectToWrappers
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.w3c.dom.Element

internal const val COLOROS_CONTACTS_EXTENSION = "extensions/coloroscontacts.mpe"

private const val EXT = "Lorg/ungoogled/coloroscontacts"
private const val RUNTIME = "$EXT/ColorOS;"
private const val IN_CALL_SERVICE = "android.telecom.InCallService"

private val log = java.util.logging.Logger.getLogger("OnePlusContacts")

private object ContactsApplicationOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/android/contacts/ContactsApplication;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

/** PermissionsUtil's "are all of these granted" check, which every screen asks before it opens. */
private object AllPermissionsGrantedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "[Ljava/lang/String;"),
    strings = listOf("hasPermission", "sMissingPermission = "),
)

/** CallLogQueryHandler's start of the call history query (the list on the Calls tab). */
private object CallHistoryLoadFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("CallLogQueryHandler", "startQuery, where = "),
)

/** The call list's header tip asking to make the app the default phone app: (context, default phone app's package). */
private object DefaultDialerTipFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;"),
    strings = listOf("defaultDialerPackage"),
)

private fun org.w3c.dom.Document.elements(tag: String): List<Element> =
    getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

/**
 * Never the phone app. Android makes an app the phone app (the dialer role) only if it has an in-call service; OnePlus
 * Contacts' is an empty stub, because on ColorOS the in-call screen is a separate system app (InCallUI, part of the
 * phone process). As the phone app it would show no screen for any call, emergency calls included. The service is
 * removed, so Android never offers the app as the phone app, and the user's own phone app keeps showing every call.
 * Its mini launcher screen is no secondary display's home screen either.
 */
private val manifestPatch = resourcePatch {
    execute {
        makeLibrariesOptional()
        document("AndroidManifest.xml").use { manifest ->
            val inCall = manifest.elements("service").filter { service ->
                service.getElementsByTagName("action").let { actions ->
                    (0 until actions.length).any { (actions.item(it) as Element).getAttribute("android:name") == IN_CALL_SERVICE }
                }
            }
            if (inCall.isEmpty()) throw PatchException("no in-call service in the manifest")
            inCall.forEach { it.parentNode.removeChild(it) }

            // Nothing left that Telecom could bind as this app's in-call screen.
            if (manifest.elements("meta-data").any { it.getAttribute("android:name").startsWith("android.telecom.IN_CALL_SERVICE") }) {
                throw PatchException("an in-call service is still declared")
            }
            if (manifest.elements("action").any { it.getAttribute("android:name") == IN_CALL_SERVICE }) {
                throw PatchException("an in-call service is still declared")
            }

            manifest.elements("category")
                .filter { it.getAttribute("android:name") in setOf("android.intent.category.SECONDARY_HOME", "android.intent.category.HOME") }
                .forEach { it.parentNode.removeChild(it) }
        }
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
 * Every call the app places goes through Calls (see there): on ColorOS it starts CALL_PRIVILEGED intents and
 * TelecomManager.placeCall as the phone app; here Telecom gets an ordinary app's ACTION_CALL and the user's phone app
 * shows the call. An emergency number, or a call Android refuses, opens the user's phone app with the number dialled
 * in, as Android itself does. Intents the app hands to others (shortcuts, notifications) carry ACTION_CALL too.
 */
private val callsPatch = bytecodePatch(
    description = "Places calls through Android's Telecom, never as the phone app.",
) {
    extendWith(COLOROS_CONTACTS_EXTENSION)

    execute {
        ContactsApplicationOnCreateFingerprint.method.addInstructions(
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
        log.info("Activity starts: $starts, Telecom calls: $telecom, privileged call actions: $actions")
        if (starts < 50) throw PatchException("only $starts activity starts found")
        if (telecom < 5) throw PatchException("only $telecom Telecom calls found")
        if (actions < 3) throw PatchException("only $actions CALL_PRIVILEGED literals found")
    }
}

/**
 * The call list asked to make the app the default phone app whenever it is not: here it never can be (see
 * manifestPatch). The tip reads the app as the default phone app, so it never shows.
 */
private val noDefaultDialerTipPatch = bytecodePatch(
    description = "Never asks to become the default phone app.",
) {
    execute {
        DefaultDialerTipFingerprint.method.addInstructionsWithLabels(
            0,
            """
                if-eqz p1, :keep
                invoke-virtual { p1 }, Landroid/content/Context;->getPackageName()Ljava/lang/String;
                move-result-object p2
            """,
            ExternalLabel("keep", DefaultDialerTipFingerprint.method.getInstruction(0)),
        )
    }
}

/**
 * The call logs permissions are hard-restricted on Android: only the default phone app, or an app whose installer
 * allowed them (adb and root installs do; app installers, Shizuku sessions and the system installer do not), may hold
 * them, and OnePlus Contacts asked for them again and again before it opened. Its "are all of these granted" check
 * leaves them out (see Permissions); they are still asked for. The call history says once what is missing and how to
 * allow it (see CallHistoryNote).
 */
private val optionalCallLogPatch = bytecodePatch(
    description = "Opens without the call logs permission.",
) {
    extendWith(COLOROS_CONTACTS_EXTENSION)

    execute {
        AllPermissionsGrantedFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXT/Permissions;->required([Ljava/lang/String;)[Ljava/lang/String;
                move-result-object p1
            """,
        )
        CallHistoryLoadFingerprint.method.addInstructions(0, "invoke-static { }, $EXT/CallHistoryNote;->onCallHistoryLoad()V")
    }
}

/** Refused phone state calls (a system app's permissions on ColorOS) answer "nothing" instead of throwing. */
private val telephonyPatch = bytecodePatch(
    description = "Answers phone state calls the phone refuses.",
) {
    extendWith(COLOROS_CONTACTS_EXTENSION)

    execute {
        val moved = redirectToWrappers(
            "$EXT/Telephony;",
            setOf("Landroid/telephony/TelephonyManager;", "Landroid/telephony/SubscriptionManager;", "Landroid/telephony/SmsManager;"),
        )
        log.info("Telephony calls: $moved")
        if (moved < 20) throw PatchException("only $moved telephony calls found")
    }
}

/**
 * The contacts provider and the call log are Android's here, which lack ColorOS's columns (ring_time, simid,
 * oplus_feature, ...) and call history paths: ProviderCalls fits the app's queries and writes to them, through a
 * ContentResolver or a ContentProviderClient (see there). Other providers go on to ColorOS's refusal handling.
 */
private val providersPatch = bytecodePatch(
    description = "Reads and writes Android's contacts provider and call log as ColorOS's.",
) {
    extendWith(COLOROS_CONTACTS_EXTENSION)

    execute {
        val calls = redirectToWrappers("$EXT/ProviderCalls;", setOf("Landroid/content/ContentResolver;"))
        // ColorOS's ProviderUtils reads and writes through a ContentProviderClient.
        val clients = redirectToWrappers("$EXT/ProviderCalls;", setOf("Landroid/content/ContentProviderClient;"))
        log.info("Provider calls: $calls, provider client calls: $clients")
        if (calls < 100) throw PatchException("only $calls provider calls found")
        if (clients < 5) throw PatchException("only $clients provider client calls found")
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
    extendWith(COLOROS_CONTACTS_EXTENSION)

    execute {
        val calls = buildMap {
            for (table in listOf("Secure", "System", "Global")) {
                for ((shape, suffix) in SETTINGS_SHAPES) {
                    put("Landroid/provider/Settings\$$table;->$shape", "$EXT/SettingsAccess;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}")
                }
            }
        }
        val moved = redirectCalls(calls)
        log.info("Settings calls: $moved")
        if (moved < 30) throw PatchException("only $moved Settings calls found")
    }
}

/**
 * OnePlus Contacts (ColorOS 16's contacts and dial pad) as a contacts, dial pad and call history app beside the user's
 * own phone app, which keeps showing every call: ColorOS's in-call screen is a separate system app (InCallUI, part of
 * the phone process), so the app is never offered as the phone app. Off ColorOS it dies at once on ColorOS framework
 * classes (the extension carries stand-ins, generated by .github/scripts/coloros-standins), registers receivers
 * without the export flag Android 14+ demands, crashes on refused phone state and settings calls, keeps asking for
 * the call logs permission most installers cannot allow, and as ColorOS's phone app it would place calls with
 * privileges it does not have here. Emergency numbers go to the user's phone app.
 */
@Suppress("unused")
val enableNonColorOsPatch = bytecodePatch(
    name = "Enable non-ColorOS ROM support",
    description = "Runs OnePlus Contacts on non-ColorOS ROMs; your default phone app still handles calls.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_CONTACTS)
    dependsOn(manifestPatch, noDefaultDialerTipPatch, callsPatch, optionalCallLogPatch, telephonyPatch, providersPatch, settingsPatch)
    extendWith(COLOROS_CONTACTS_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers(RUNTIME)
        log.info("registerReceiver calls through ColorOS: $receivers")
        if (receivers < 20) throw PatchException("only $receivers registerReceiver calls found")

        // On OPPO, OnePlus and realme phones ColorOS's own apps are there but refuse it: a refusal reads as their absence.
        val (services, providers) = treatRefusedComponentsAsMissing(RUNTIME)
        log.info("Service calls: $services, provider calls: $providers through ColorOS")
    }
}
