package org.ungoogled.patches.samsungreminder.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsungreminder.SamsungReminderConstants.COMPATIBILITY_SAMSUNG_REMINDER
import org.w3c.dom.Element

internal const val SAMSUNG_REMINDER_EXTENSION = "extensions/samsungreminder.mpe"

/** Phone-state calls Android keeps behind READ_PHONE_STATE, which One UI grants Reminder as a system app. */
private val PHONE_CALLS = mapOf(
    "Landroid/telephony/TelephonyManager;" to setOf(
        "getCallState()I",
        "registerTelephonyCallback(Ljava/util/concurrent/Executor;Landroid/telephony/TelephonyCallback;)V",
        "unregisterTelephonyCallback(Landroid/telephony/TelephonyCallback;)V",
    ),
)

/**
 * On One UI Reminder lives inside Samsung Calendar and its launcher icon is off until switched on; elsewhere there is
 * no other way in, so the launcher entry starts enabled.
 */
private val launcherIconPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val aliases = manifest.getElementsByTagName("activity-alias")
            var found = false
            for (i in 0 until aliases.length) {
                val alias = aliases.item(i) as Element
                if (alias.getAttribute("android:name") != "com.samsung.android.app.reminder.ui.LaunchMainActivity") continue
                alias.setAttribute("android:enabled", "true")
                found = true
            }
            if (!found) throw PatchException("no LaunchMainActivity launcher alias in the manifest")
        }
    }
}

/**
 * Sends Reminder's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). Its
 * Fallbacks also give the panels One UI would blur (the quick add sheet) a plain background instead of none.
 */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_REMINDER_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungreminder/OneUi;", "Lorg/ungoogled/samsungreminder/Sem;")
        java.util.logging.Logger.getLogger("SamsungReminder").info("One UI members: $calls calls, $fields fields")
        // Measured against 12.8.00.62: 146 calls and 42 field accesses.
        if (calls < 60 || fields < 15) throw PatchException("only $calls One UI calls and $fields field accesses found")
    }
}

/** One UI's own system services Reminder casts without a check ("sem_statusbar", "motion_recognition") get stand-ins. */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_REMINDER_EXTENSION)

    execute {
        val moved = redirectSystemServices("Lorg/ungoogled/samsungreminder/Services;")
        java.util.logging.Logger.getLogger("SamsungReminder").info("System service calls: $moved")
        // Measured against 12.8.00.62: 356 calls.
        if (moved < 100) throw PatchException("only $moved getSystemService calls found")
    }
}

/** The call state needs READ_PHONE_STATE (a system app's grant on One UI): Phone answers it from the audio mode. */
private val phoneStatePatch = bytecodePatch(
    description = "Reads the call state without READ_PHONE_STATE.",
) {
    extendWith(SAMSUNG_REMINDER_EXTENSION)

    execute {
        val moved = redirectCalls("Lorg/ungoogled/samsungreminder/Phone;", PHONE_CALLS)
        if (moved < 3) throw PatchException("only $moved phone-state calls found")
    }
}

/** Context.getDisplay() throws for contexts without a display (receivers, the application): Displays answers the default. */
private val displayPatch = bytecodePatch(
    description = "Answers the default display for contexts without one.",
) {
    extendWith(SAMSUNG_REMINDER_EXTENSION)

    execute {
        val moved = redirectCalls(
            "Lorg/ungoogled/samsungreminder/Displays;",
            mapOf("Landroid/content/Context;" to setOf("getDisplay()Landroid/view/Display;")),
        )
        if (moved < 5) throw PatchException("only $moved getDisplay calls found")
    }
}

/** Some icons are SPR files, Samsung's vector format that only One UI can draw: they become vector drawables. */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        java.util.logging.Logger.getLogger("SamsungReminder").info("SPR drawables: $converted")
        // Measured against 12.8.00.62: 10 drawables.
        if (converted < 5) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Off One UI Reminder dies at once on One UI members (Build.VERSION.SEM_INT first, in a provider at start-up), its
 * edge panel receiver and its "digital legacy" user check, and has no launcher icon. The extension also carries stand-ins for the One UI classes it calls.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Reminder installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_REMINDER)
    dependsOn(launcherIconPatch, oneUiMembersPatch, systemServicesPatch, phoneStatePatch, displayPatch, sprDrawablesPatch)
    extendWith(SAMSUNG_REMINDER_EXTENSION)
}
