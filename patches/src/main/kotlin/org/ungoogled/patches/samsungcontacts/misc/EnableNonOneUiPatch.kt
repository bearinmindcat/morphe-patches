package org.ungoogled.patches.samsungcontacts.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsung.redirectToWrappers
import org.ungoogled.patches.samsungcontacts.SamsungContactsConstants.COMPATIBILITY_SAMSUNG_CONTACTS
import org.w3c.dom.Element

internal const val SAMSUNG_CONTACTS_EXTENSION = "extensions/samsungcontacts.mpe"

private const val LOG = "SamsungContacts"
private const val EXT = "Lorg/ungoogled/samsungcontacts"

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

/** Refused phone state calls (a preloaded app's permissions on One UI) answer "nothing" instead of throwing. */
private val telephonyPatch = bytecodePatch(
    description = "Answers phone state calls the phone refuses.",
) {
    extendWith(SAMSUNG_CONTACTS_EXTENSION)

    execute {
        val moved = redirectToWrappers(
            "$EXT/Telephony;",
            setOf("Landroid/telephony/TelephonyManager;", "Landroid/telephony/SubscriptionManager;", "Landroid/telephony/SmsManager;"),
        )
        java.util.logging.Logger.getLogger(LOG).info("Telephony calls: $moved")
        // Measured against 16.3.00.87: 69 calls.
        if (moved < 30) throw PatchException("only $moved telephony calls found")
    }
}

/**
 * Contacts reads and writes Android's contacts provider as if it were Samsung's (sec_* columns, UNICODE_NATURAL
 * collation, applyBatch saves with Samsung columns): off One UI the list stayed empty and saving failed. Its calls are
 * fitted to Android's provider (see ProviderCalls); missing providers are not watched (see Providers).
 */
private val providersPatch = bytecodePatch(
    description = "Fits the app's contacts provider calls to Android's.",
) {
    extendWith(SAMSUNG_CONTACTS_EXTENSION)

    execute {
        val observers = redirectToWrappers("$EXT/Providers;", setOf("Landroid/content/ContentResolver;"))
        val calls = redirectToWrappers("$EXT/ProviderCalls;", setOf("Landroid/content/ContentResolver;"))
        java.util.logging.Logger.getLogger(LOG).info("Provider calls: $calls, observers: $observers")
        // Measured against 16.3.00.87: 538 calls and 29 observers.
        if (calls < 200 || observers < 10) throw PatchException("only $calls provider calls found")
    }
}

/** Context.getDisplay() throws for contexts without a display: Displays answers the default. */
private val displayPatch = bytecodePatch(
    description = "Answers the default display for contexts without one.",
) {
    extendWith(SAMSUNG_CONTACTS_EXTENSION)

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
    extendWith(SAMSUNG_CONTACTS_EXTENSION)

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
        // Measured against 16.3.00.87: 212 calls.
        if (moved < 80) throw PatchException("only $moved Settings calls found")
    }
}

/** Sends the app's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_CONTACTS_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        java.util.logging.Logger.getLogger(LOG).info("One UI members: $calls calls, $fields fields")
        // Measured against 16.3.00.87: 366 calls and 17 field accesses.
        if (calls < 150) throw PatchException("only $calls One UI calls and $fields field accesses found")
    }
}

/** One UI's own system services the app casts without a check get stand-ins. */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_CONTACTS_EXTENSION)

    execute {
        val moved = redirectSystemServices("$EXT/Services;") + redirectCalls(
            "$EXT/Services;",
            mapOf("Landroid/content/Context;" to setOf("getSystemService(Ljava/lang/Class;)Ljava/lang/Object;")),
        )
        java.util.logging.Logger.getLogger(LOG).info("System service calls: $moved")
        // Measured against 16.3.00.87: 244 calls.
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
        // Measured against 16.3.00.87: 11 drawables.
        if (converted < 5) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Off One UI Contacts crashes on refused phone state and settings calls, and its list, editor and details fail on
 * Android's contacts provider. The extension also carries stand-ins for the One UI classes it calls.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Contacts installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_CONTACTS)
    dependsOn(
        manifestPatch, telephonyPatch, providersPatch, displayPatch, settingsPatch, oneUiMembersPatch, systemServicesPatch,
        sprDrawablesPatch,
    )
    extendWith(SAMSUNG_CONTACTS_EXTENSION)
}
