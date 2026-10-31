package org.ungoogled.patches.samsungmessages.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsung.redirectToWrappers
import org.ungoogled.patches.samsungmessages.SamsungMessagesConstants.COMPATIBILITY_SAMSUNG_MESSAGES
import org.w3c.dom.Element

internal const val SAMSUNG_MESSAGES_EXTENSION = "extensions/samsungmessages.mpe"

private const val LOG = "SamsungMessages"

/** Samsung's framework libraries and native libraries the manifest requires exist only on One UI: all become optional. */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            var changed = 0
            for (tag in listOf("uses-library", "uses-native-library")) {
                val nodes = manifest.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    if (node.getAttribute("android:required") == "false") continue
                    node.setAttribute("android:required", "false")
                    changed++
                }
            }
            if (changed == 0) throw PatchException("no required libraries in the manifest")
        }
    }
}

/**
 * At start Messages asks the hidden device idle controller about its battery exemption and adds itself; elsewhere that
 * interface lacks the method (NoSuchMethodError, not caught) and the app crashed. Android's public answer stands in
 * and the hidden add is skipped.
 */
private val powerSavePatch = bytecodePatch(
    description = "Reads the battery optimisation exemption from Android's public API.",
) {
    extendWith(SAMSUNG_MESSAGES_EXTENSION)

    execute {
        PowerSaveAllowListedFingerprint.method.addInstructions(
            0,
            """
                invoke-static { }, Lcom/samsung/android/messaging/common/util/appcontext/AppContext;->getContext()Landroid/content/Context;
                move-result-object v0
                invoke-static { v0 }, Lorg/ungoogled/samsungmessages/Power;->isIgnoringBatteryOptimizations(Landroid/content/Context;)Z
                move-result v0
                return v0
            """,
        )
        AddToPowerSaveAllowlistFingerprint.method.addInstructions(0, "return-void")
    }
}

/**
 * On One UI Messages holds the phone permissions as a preloaded app; elsewhere they come later or never, and device
 * identifiers never: the first refused TelephonyManager / SubscriptionManager call crashed the app at start. Those calls
 * go through Telephony, which answers a refusal with "nothing".
 */
private val telephonyPatch = bytecodePatch(
    description = "Answers phone state calls the phone refuses.",
) {
    extendWith(SAMSUNG_MESSAGES_EXTENSION)

    execute {
        val moved = redirectToWrappers(
            "Lorg/ungoogled/samsungmessages/Telephony;",
            setOf("Landroid/telephony/TelephonyManager;", "Landroid/telephony/SubscriptionManager;", "Landroid/telephony/SmsManager;"),
        )
        java.util.logging.Logger.getLogger(LOG).info("Telephony calls: $moved")
        // Measured against 16.3.21.1: 139 calls.
        if (moved < 50) throw PatchException("only $moved telephony calls found")
    }
}

/** Watching a provider only One UI has (Samsung's logs, spam lists, RCS) threw; Providers skips it instead. */
private val providerObserversPatch = bytecodePatch(
    description = "Skips watching providers the phone does not have.",
) {
    extendWith(SAMSUNG_MESSAGES_EXTENSION)

    execute {
        val moved = redirectToWrappers("Lorg/ungoogled/samsungmessages/Providers;", setOf("Landroid/content/ContentResolver;"))
        // Measured against 16.3.21.1: 120 calls.
        if (moved < 50) throw PatchException("only $moved content observer registrations found")
    }
}

/**
 * Messages reads and writes Android's telephony provider as if it were Samsung's, with more columns and paths: off One
 * UI the first unknown path ("content://mms-sms/im-threads") crashed the sync service and unknown columns failed the
 * rest. Its ContentResolver calls go through SmsProviders, which fits them to Android's provider.
 */
private val smsProviderPatch = bytecodePatch(
    description = "Fits the app's telephony provider calls to Android's.",
) {
    extendWith(SAMSUNG_MESSAGES_EXTENSION)

    execute {
        val moved = redirectToWrappers("Lorg/ungoogled/samsungmessages/SmsProviders;", setOf("Landroid/content/ContentResolver;"))
        java.util.logging.Logger.getLogger(LOG).info("Provider calls: $moved")
        // Measured against 16.3.21.1: 609 calls.
        if (moved < 300) throw PatchException("only $moved provider calls found")
    }
}

/** Context.getDisplay() throws for contexts without a display (services, receivers): Displays answers the default. */
private val displayPatch = bytecodePatch(
    description = "Answers the default display for contexts without one.",
) {
    extendWith(SAMSUNG_MESSAGES_EXTENSION)

    execute {
        val moved = redirectCalls(
            "Lorg/ungoogled/samsungmessages/Displays;",
            mapOf("Landroid/content/Context;" to setOf("getDisplay()Landroid/view/Display;")),
        )
        // Measured against 16.3.21.1: 11 calls.
        if (moved < 5) throw PatchException("only $moved getDisplay calls found")
    }
}

/**
 * Off One UI Messages starts its send / receive IntentServices as foreground services, which never call startForeground:
 * Android killed the app a few seconds after the first send. They are started through Relay instead.
 */
private val workServicesPatch = bytecodePatch(
    description = "Starts the app's work services without the foreground promise they never keep.",
) {
    extendWith(SAMSUNG_MESSAGES_EXTENSION)

    execute {
        RequestServiceFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val index = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((it as ReferenceInstruction).reference as MethodReference).name == "startForegroundService"
            }
            if (index < 0) throw PatchException("no startForegroundService in ServiceCompat.requestService")
            val call = instructions[index] as FiveRegisterInstruction
            replaceInstruction(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD} }, Lorg/ungoogled/samsungmessages/Relay;->startService(Landroid/content/Context;Landroid/content/Intent;)Landroid/content/ComponentName;",
            )
        }
    }
}

/** The relay service (see Relay), a short foreground service. */
private val relayManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("service").apply {
                    setAttribute("android:name", "org.ungoogled.samsungmessages.Relay")
                    setAttribute("android:exported", "false")
                    setAttribute("android:foregroundServiceType", "shortService")
                },
            )
        }
    }
}

/**
 * The SMS send wrapper only knows One UI's semSendMultipartTextMessage and does nothing elsewhere: every SMS stayed
 * "sending". It sends through Android's own SmsManager.sendMultipartTextMessage instead (One UI's extras, such as the
 * reply path and validity period, are left out).
 */
private val sendSmsPatch = bytecodePatch(
    description = "Sends SMS through Android's SmsManager.",
) {
    execute {
        for (fingerprint in listOf(SendMultipartFingerprint, SendMultipartWithValidityFingerprint)) {
            fingerprint.method.addInstructions(
                0,
                """
                    invoke-virtual/range { p0 .. p5 }, Landroid/telephony/SmsManager;->sendMultipartTextMessage(Ljava/lang/String;Ljava/lang/String;Ljava/util/ArrayList;Ljava/util/ArrayList;Ljava/util/ArrayList;)V
                    return-void
                """,
            )
        }
    }
}

private const val SETTINGS_ACCESS = "Lorg/ungoogled/samsungmessages/SettingsAccess;"

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

/** A refused settings write or read threw SecurityException (the Contacts tab crashed): SettingsAccess answers it. */
private val settingsPatch = bytecodePatch(
    description = "Answers settings reads and writes the phone refuses.",
) {
    extendWith(SAMSUNG_MESSAGES_EXTENSION)

    execute {
        val calls = buildMap {
            for (table in listOf("Secure", "System", "Global")) {
                for ((shape, suffix) in SETTINGS_SHAPES) {
                    put("Landroid/provider/Settings\$$table;->$shape", "$SETTINGS_ACCESS->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}")
                }
            }
        }
        val moved = redirectCalls(calls)
        java.util.logging.Logger.getLogger(LOG).info("Settings calls: $moved")
        // Measured against 16.3.21.1: 149 calls.
        if (moved < 50) throw PatchException("only $moved Settings calls found")
    }
}

/** Sends the app's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_MESSAGES_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungmessages/OneUi;", "Lorg/ungoogled/samsungmessages/Sem;")
        java.util.logging.Logger.getLogger(LOG).info("One UI members: $calls calls, $fields fields")
        // Measured against 16.3.21.1: 163 calls and 42 field accesses.
        if (calls < 60) throw PatchException("only $calls One UI calls and $fields field accesses found")
    }
}

/** One UI's own system services the app casts without a check get stand-ins. */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_MESSAGES_EXTENSION)

    execute {
        val moved = redirectSystemServices("Lorg/ungoogled/samsungmessages/Services;") + redirectCalls(
            "Lorg/ungoogled/samsungmessages/Services;",
            mapOf("Landroid/content/Context;" to setOf("getSystemService(Ljava/lang/Class;)Ljava/lang/Object;")),
        )
        java.util.logging.Logger.getLogger(LOG).info("System service calls: $moved")
        // Measured against 16.3.21.1: 357 by name and 66 by class.
        if (moved < 150) throw PatchException("only $moved getSystemService calls found")
    }
}

/** Some icons are SPR files, Samsung's vector format that only One UI can draw: they become vector drawables. */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        java.util.logging.Logger.getLogger(LOG).info("SPR drawables: $converted")
        // Measured against 16.3.21.1: 41 drawables.
        if (converted < 20) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Off One UI Messages already has a fallback for phones without Samsung's framework, but as a non-system app it crashes
 * at start on the hidden device idle controller, refused phone state calls and missing providers; it then talks to
 * Android's telephony provider as if it were Samsung's, starts its work services as foreground services they never
 * become, and its SMS send fallback does nothing. With the fixes it receives and sends SMS as the default SMS app. The
 * extension also carries stand-ins for the One UI classes it calls. RCS, Samsung's spam and logs providers, Samsung
 * Cloud and AI features are not available.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Messages installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_MESSAGES)
    dependsOn(
        manifestPatch, powerSavePatch, telephonyPatch, providerObserversPatch, smsProviderPatch, displayPatch,
        workServicesPatch, relayManifestPatch, sendSmsPatch, settingsPatch, oneUiMembersPatch, systemServicesPatch,
        sprDrawablesPatch,
    )
    extendWith(SAMSUNG_MESSAGES_EXTENSION)
}
