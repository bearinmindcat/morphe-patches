package org.ungoogled.patches.samsungemail.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsungemail.SamsungEmailConstants.COMPATIBILITY_SAMSUNG_EMAIL

internal const val SAMSUNG_EMAIL_EXTENSION = "extensions/samsungemail.mpe"

private const val EXT = "Lorg/ungoogled/samsungemail"

private val log = java.util.logging.Logger.getLogger("SamsungEmail")

/**
 * Many icons are SPR files, Samsung's vector format that only One UI can draw (opening a message failed on the
 * compose button's icon): they become vector drawables.
 */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        log.info("SPR drawables: $converted")
        // Measured against 6.2.13.1: 54 files.
        if (converted < 20) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Sends Email's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers), and its system
 * service lookups through Services (Samsung's clipboard service, used unchecked when the composer opens).
 */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_EMAIL_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        val services = redirectSystemServices("$EXT/Services;")
        log.info("One UI members: $calls calls, $fields fields; service lookups: $services")
        if (services < 50) throw PatchException("only $services service lookups found")
        if (calls < 20) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * Email watches Knox's policy provider (com.sec.knox.provider2), which only One UI has; since Android 14 watching a
 * missing provider throws, so Email's service crashed at start. Such a watch is now skipped (extension Providers).
 */
private val providerObserversPatch = bytecodePatch(
    description = "Skips watching One UI providers the phone doesn't have.",
) {
    extendWith(SAMSUNG_EMAIL_EXTENSION)

    execute {
        val moved = redirectCalls(
            mapOf(
                "Landroid/content/ContentResolver;->registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;)V" to
                    "$EXT/Providers;->registerContentObserver(Landroid/content/ContentResolver;Landroid/net/Uri;ZLandroid/database/ContentObserver;)V",
            ),
        )
        log.info("Content observers: $moved")
        if (moved < 1) throw PatchException("no ContentResolver.registerContentObserver call found")
    }
}

private const val QUERY = "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;"
private const val QUERY_CANCELLABLE = "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;"

/** ContentResolver / ContentProviderClient calls that reach a provider (Calendars passes all but the calendar's on). */
private val PROVIDER_CALLS = mapOf(
    "Landroid/content/ContentResolver;" to setOf(
        QUERY, QUERY_CANCELLABLE,
        "query(Landroid/net/Uri;[Ljava/lang/String;Landroid/os/Bundle;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
        "insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
        "update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I",
        "delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I",
        "bulkInsert(Landroid/net/Uri;[Landroid/content/ContentValues;)I",
        "applyBatch(Ljava/lang/String;Ljava/util/ArrayList;)[Landroid/content/ContentProviderResult;",
        "call(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
        "call(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
    ),
    "Landroid/content/ContentProviderClient;" to setOf(QUERY, QUERY_CANCELLABLE),
)

/**
 * An Exchange account's calendar syncs into "com.android.calendar" as if it were Samsung's calendar provider, which has
 * more columns and tables than Android's: off One UI the first calendar sync failed ("no such column: secExtra1") and
 * no event arrived. Email's provider calls go through Calendars (the same shim as Samsung Calendar's port), which fits
 * the calendar's to Android's provider and passes every other one on unchanged.
 */
private val calendarProviderPatch = bytecodePatch(
    description = "Fits Exchange calendar sync to Android's calendar provider.",
) {
    extendWith(SAMSUNG_EMAIL_EXTENSION)

    execute {
        val moved = redirectCalls("$EXT/Calendars;", PROVIDER_CALLS)
        log.info("Provider calls: $moved")
        // Measured against 6.2.13.1: 1065 calls.
        if (moved < 300) throw PatchException("only $moved provider calls found")
    }
}

/** PackageInfo.needCheckingAppUpdate: whether Email asks the Galaxy Store for a newer Email (only with the Store present). */
private object NeedCheckingAppUpdateFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    custom = { method, classDef ->
        method.name == "needCheckingAppUpdate" && AccessFlags.STATIC.isSet(method.accessFlags) &&
            classDef.type.endsWith("/basic/system/PackageInfo;")
    },
)

/**
 * Where the Galaxy Store is installed, Email asks Samsung's update server (vas.samsungapps.com) twice a day for a newer
 * Email, sending the phone's model, carrier and Samsung account country, without asking. The newer Email it offers is
 * Samsung's, which can't replace this copy anyway, so the check is off; "About Email" still opens the Store on request.
 */
private val updateCheckPatch = bytecodePatch(
    description = "Stops the background update check against Samsung's server.",
) {
    execute {
        NeedCheckingAppUpdateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
    }
}

/** Device.getConsistentDeviceId(Context): the Exchange ActiveSync device ID, made from the IMEI or serial number. */
private object ConsistentDeviceIdFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("get deviceId for phone model"),
)

/**
 * Exchange and Outlook.com / Hotmail accounts are Exchange ActiveSync accounts, and setup refused them ("Device
 * activation failed. Check your device ID, i.e. IMEI, MEID, or HW ID"): Email makes its ActiveSync device ID from the
 * IMEI (tablets: the serial number), which only a preloaded app may read. The ID is now random, made once per install
 * (extension DeviceIds), and the phone's identifiers it sends in the device report (IMEI, phone number / IMSI) are
 * unknown instead of a SecurityException that cancelled the report and, from Exchange 2010 on, the provisioning.
 */
private val exchangeDeviceIdPatch = bytecodePatch(
    description = "Gives Exchange accounts a random device ID instead of one made from the IMEI.",
) {
    extendWith(SAMSUNG_EMAIL_EXTENSION)

    execute {
        ConsistentDeviceIdFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXT/DeviceIds;->easDeviceId(Landroid/content/Context;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """,
        )
        val ids = redirectCalls(
            "$EXT/Telephony;",
            mapOf(
                "Landroid/telephony/TelephonyManager;" to setOf(
                    "getPrimaryImei()Ljava/lang/String;",
                    "getDeviceId()Ljava/lang/String;",
                    "getDeviceId(I)Ljava/lang/String;",
                    "getImei()Ljava/lang/String;",
                    "getImei(I)Ljava/lang/String;",
                    "getMeid()Ljava/lang/String;",
                    "getMeid(I)Ljava/lang/String;",
                    "getSubscriberId()Ljava/lang/String;",
                    "getSimSerialNumber()Ljava/lang/String;",
                    "getLine1Number()Ljava/lang/String;",
                ),
            ),
        )
        log.info("Phone identifier reads: $ids")
        // Measured against 6.2.13.1: 9 (Device 2, DeviceInformation 4, EasDevInfoSvc 3).
        if (ids < 5) throw PatchException("only $ids phone identifier reads found")
    }
}

/**
 * Off One UI Email dies at once on One UI classes (SemFloatingFeature, Knox, Samsung's S/MIME library). The extension
 * carries stand-ins for the One UI classes it uses (generated by .github/scripts/samsung-standins); on a Samsung phone
 * the framework's own classes load first, so they never run there. S/MIME, smart cards and Knox device management stay
 * off; plain IMAP, POP3 and Exchange accounts don't need them.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Email usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_EMAIL)
    dependsOn(
        sprDrawablesPatch, oneUiMembersPatch, providerObserversPatch, updateCheckPatch, exchangeDeviceIdPatch,
        calendarProviderPatch,
    )
    extendWith(SAMSUNG_EMAIL_EXTENSION)
}
