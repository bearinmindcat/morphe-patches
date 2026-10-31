package org.ungoogled.patches.samsungcalendar.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsungcalendar.SamsungCalendarConstants.COMPATIBILITY_SAMSUNG_CALENDAR
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val SAMSUNG_CALENDAR_EXTENSION = "extensions/samsungcalendar.mpe"

private const val QUERY = "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;"
private const val QUERY_CANCELLABLE = "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;"

/** ContentResolver / ContentProviderClient calls that reach a provider. */
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

/** Phone-state calls Android keeps behind READ_PHONE_STATE, which One UI grants Calendar as a system app. */
private val PHONE_CALLS = mapOf(
    "Landroid/telephony/TelephonyManager;" to setOf(
        "getCallState()I",
        "registerTelephonyCallback(Ljava/util/concurrent/Executor;Landroid/telephony/TelephonyCallback;)V",
        "unregisterTelephonyCallback(Landroid/telephony/TelephonyCallback;)V",
    ),
)

/**
 * The full-screen alert disables the status bar through One UI's "sem_statusbar" service, cast without a check: off One
 * UI that is null and the alert crashed Calendar. Services answers it (and the clipboard, motion and desktop mode
 * services, used the same way) with a stand-in.
 */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_CALENDAR_EXTENSION)

    execute {
        val moved = redirectSystemServices("Lorg/ungoogled/samsungcalendar/Services;")
        // Measured against 12.8.01.0: 300+ calls.
        if (moved < 100) throw PatchException("only $moved getSystemService calls found")
        java.util.logging.Logger.getLogger("SamsungCalendar").info("System service calls: $moved")
    }
}

/**
 * Before posting an alert Calendar asks its receiver's context for its display, which Android refuses for a context
 * without one (UnsupportedOperationException): every alert failed there. Displays answers the default display.
 */
private val displayPatch = bytecodePatch(
    description = "Answers the default display for contexts without one.",
) {
    extendWith(SAMSUNG_CALENDAR_EXTENSION)

    execute {
        val moved = redirectCalls(
            "Lorg/ungoogled/samsungcalendar/Displays;",
            mapOf("Landroid/content/Context;" to setOf("getDisplay()Landroid/view/Display;")),
        )
        // Measured against 12.8.01.0: 14 calls.
        if (moved < 5) throw PatchException("only $moved getDisplay calls found")
    }
}

/** Sends Calendar's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_CALENDAR_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungcalendar/OneUi;", "Lorg/ungoogled/samsungcalendar/Sem;")
        java.util.logging.Logger.getLogger("SamsungCalendar").info("One UI members: $calls calls, $fields fields")
        // Measured against 12.8.01.0: 182 calls and 50 field accesses.
        if (calls < 90 || fields < 25) throw PatchException("only $calls One UI calls and $fields field accesses found")
    }
}

/**
 * Calendar reads and writes "com.android.calendar" as Samsung's calendar provider, which has more columns and tables
 * than Android's: off One UI every query naming one failed ("Invalid column contact_id", "Unknown URL
 * .../syncTasks") and the views stayed empty. Its provider calls go through Calendars, which fits them to Android's.
 */
private val calendarProviderPatch = bytecodePatch(
    description = "Fits Calendar's calls to Samsung's calendar provider to Android's.",
) {
    extendWith(SAMSUNG_CALENDAR_EXTENSION)

    execute {
        val moved = redirectCalls("Lorg/ungoogled/samsungcalendar/Calendars;", PROVIDER_CALLS)
        java.util.logging.Logger.getLogger("SamsungCalendar").info("Provider calls: $moved")
        // Measured against 12.8.01.0: 650 calls.
        if (moved < 300) throw PatchException("only $moved provider calls found")
    }
}

/**
 * Before posting an event alert Calendar asks for the call state, which needs READ_PHONE_STATE (a system app's grant on
 * One UI): elsewhere the SecurityException dropped every alert. Phone answers it from the audio mode instead.
 */
private val phoneStatePatch = bytecodePatch(
    description = "Reads the call state without READ_PHONE_STATE.",
) {
    extendWith(SAMSUNG_CALENDAR_EXTENSION)

    execute {
        val moved = redirectCalls("Lorg/ungoogled/samsungcalendar/Phone;", PHONE_CALLS)
        // Measured against 12.8.01.0: 3 getCallState, 1 register, 1 unregister.
        if (moved < 3) throw PatchException("only $moved phone-state calls found")
    }
}

/**
 * Event alerts post on a channel Calendar makes only with one of One UI's own notification sounds ("Falling Leaf",
 * "Sticky", "On Time"), found by title: elsewhere there is none, the channel was never made and every alert failed on
 * it. The phone's default notification sound stands in.
 */
private val alertSoundPatch = bytecodePatch(
    description = "Gives event alerts the phone's default notification sound.",
) {
    extendWith(SAMSUNG_CALENDAR_EXTENSION)

    execute {
        AlertSoundFingerprint.method.apply {
            val returns = implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }.reversed()
            if (returns.isEmpty()) throw PatchException("alert sound lookup has no return")
            // Back to front; the return keeps its labels, so every path to it goes through the fallback.
            for (index in returns) {
                val register = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
                replaceInstruction(index, "invoke-static/range { v$register .. v$register }, Lorg/ungoogled/samsungcalendar/Alerts;->sound(Landroid/net/Uri;)Landroid/net/Uri;")
                addInstructions(index + 1, "move-result-object v$register\nreturn-object v$register")
            }
        }
    }
}

/**
 * Calendar's header icons and others are SPR files, Samsung's vector format that only One UI can draw: elsewhere the
 * month view's action bar failed to inflate and stayed empty (no title, drawer or Today buttons). They become vector
 * drawables.
 */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        java.util.logging.Logger.getLogger("SamsungCalendar").info("SPR drawables: $converted")
        // Measured against 12.8.01.0: 88 drawables.
        if (converted < 40) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Off One UI Calendar dies at once on One UI members (Build.VERSION.SEM_INT first, in a provider at start-up), its
 * views stay empty on Android's calendar provider, and alerts never show. The extension also carries stand-ins for the
 * One UI classes it calls.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Calendar installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_CALENDAR)
    dependsOn(oneUiMembersPatch, systemServicesPatch, calendarProviderPatch, phoneStatePatch, displayPatch, alertSoundPatch, sprDrawablesPatch)
    extendWith(SAMSUNG_CALENDAR_EXTENSION)
}
