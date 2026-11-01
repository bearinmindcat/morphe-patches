package org.ungoogled.patches.xiaomicalendar.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.xiaomi.redirectMiuiMembers
import org.ungoogled.patches.xiaomicalendar.XiaomiCalendarConstants.COMPATIBILITY_XIAOMI_CALENDAR
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val XIAOMI_CALENDAR_EXTENSION = "extensions/xiaomicalendar.mpe"

private val log = java.util.logging.Logger.getLogger("XiaomiCalendar")

private const val HOME = "Lcom/android/calendar/homepage/AllInOneActivity;"

private const val QUERY = "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;"
private const val QUERY_CANCELLABLE = "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;"
private const val QUERY_BUNDLE = "query(Landroid/net/Uri;[Ljava/lang/String;Landroid/os/Bundle;Landroid/os/CancellationSignal;)Landroid/database/Cursor;"
private const val INSERT = "insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;"
private const val UPDATE = "update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I"
private const val DELETE = "delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I"
private const val BULK_INSERT = "bulkInsert(Landroid/net/Uri;[Landroid/content/ContentValues;)I"

/** ContentResolver / ContentProviderClient calls that reach a provider. */
private val PROVIDER_CALLS = mapOf(
    "Landroid/content/ContentResolver;" to setOf(
        QUERY, QUERY_CANCELLABLE, QUERY_BUNDLE, INSERT, UPDATE, DELETE, BULK_INSERT,
        "applyBatch(Ljava/lang/String;Ljava/util/ArrayList;)[Landroid/content/ContentProviderResult;",
        "call(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
        "call(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
    ),
    "Landroid/content/ContentProviderClient;" to setOf(
        QUERY, QUERY_CANCELLABLE, QUERY_BUNDLE, INSERT, UPDATE, DELETE, BULK_INSERT,
        "applyBatch(Ljava/util/ArrayList;)[Landroid/content/ContentProviderResult;",
    ),
)

/** Sends Calendar's uses of HyperOS's additions to Android classes through Miui (see redirectMiuiMembers). */
private val miuiMembersPatch = bytecodePatch(
    description = "Answers HyperOS's additions to Android classes where the phone has none.",
) {
    extendWith(XIAOMI_CALENDAR_EXTENSION)

    execute {
        val (calls, fields) = redirectMiuiMembers(
            "Lorg/ungoogled/xiaomicalendar/Miui;", "Lorg/ungoogled/xiaomicalendar/MiuiShims;",
        )
        log.info("HyperOS members: $calls calls, $fields fields")
        // Measured against 18.0.2.0: 5 calls (miuix's ActivityOptions transitions).
        if (calls < 5) throw PatchException("only $calls HyperOS calls found")
    }
}

/**
 * Calendar reads and writes "com.android.calendar" as HyperOS's calendar provider: its own URI parameters
 * (caller_is_miui_calendar, which lets it write extended properties) and a local calendar the provider makes. Off
 * HyperOS every event save failed ("Invalid URI parameter: caller_is_miui_calendar") and there was no calendar to save
 * to ("You need to add a calendar account"). Its provider calls go through Calendars, which fits them to Android's.
 */
private val calendarProviderPatch = bytecodePatch(
    description = "Fits Calendar's calls to HyperOS's calendar provider to Android's.",
) {
    extendWith(XIAOMI_CALENDAR_EXTENSION)

    execute {
        val moved = redirectCalls("Lorg/ungoogled/xiaomicalendar/Calendars;", PROVIDER_CALLS)
        log.info("Provider calls: $moved")
        // Measured against 18.0.2.0: 161 calls.
        if (moved < 100) throw PatchException("only $moved provider calls found")
    }
}

/** AllInOneActivity's start-up check for the Security app's permission screen (logs "showCtaDialogIfNeed"). */
private object CtaDialogFingerprint : Fingerprint(
    definingClass = HOME,
    returnType = "V",
    parameters = listOf(),
    strings = listOf("showCtaDialogIfNeed"),
)

/** AllInOneActivity.onRequestPermissionsResult. */
private object PermissionsResultFingerprint : Fingerprint(
    definingClass = HOME,
    name = "onRequestPermissionsResult",
    returnType = "V",
    parameters = listOf("I", "[Ljava/lang/String;", "[I"),
)

/** UserNoticeUtil.isNecessaryPermissionAccepted(Context): set when the Security app's permission screen is accepted. */
private object NecessaryPermissionFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("sp_permission_necessary_dialog"),
)

/**
 * On HyperOS Calendar holds its calendar permissions as a system app or gets them from the Security app's permission
 * screen; elsewhere it never asked and showed no events. The home screen now asks for them (and for notifications,
 * for reminders) when the Security app is missing, and reloads once they are granted.
 */
private val permissionsPatch = bytecodePatch(
    description = "Asks for the calendar and notification permissions.",
) {
    extendWith(XIAOMI_CALENDAR_EXTENSION)

    execute {
        CtaDialogFingerprint.method.addInstructions(
            0,
            "invoke-static {p0}, Lorg/ungoogled/xiaomicalendar/Permissions;->ask(Landroid/app/Activity;)V",
        )
        PermissionsResultFingerprint.method.addInstructions(
            0,
            "invoke-static {p0, p1}, Lorg/ungoogled/xiaomicalendar/Permissions;->onResult(Landroid/app/Activity;I)V",
        )
        // "Necessary permissions accepted" (it gates the month title, search and more) is only ever set by the Security
        // app's screen; without one it now also holds once the calendar permissions are granted.
        NecessaryPermissionFingerprint.method.apply {
            val returns = implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN }.map { it.index }.reversed()
            if (returns.isEmpty()) throw PatchException("necessary-permission check has no return")
            for (index in returns) {
                val register = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
                // Back to front; the return keeps its labels, so every path to it goes through the check. (The result
                // reuses the Context's register, so the check finds the app's context itself.)
                replaceInstruction(index, "invoke-static { v$register }, Lorg/ungoogled/xiaomicalendar/Permissions;->accepted(Z)Z")
                addInstructions(index + 1, "move-result v$register\nreturn v$register")
            }
        }
    }
}

/** MiuiDefaultRingtonePreference's picker intent: HyperOS's ringtone picker with its own extras. */
private object RingtonePickerFingerprint : Fingerprint(
    definingClass = "Lcom/android/calendar/preferences/MiuiDefaultRingtonePreference;",
    returnType = "V",
    parameters = listOf("Landroid/content/Intent;"),
    strings = listOf("miui.intent.action.RINGTONE_PICKER"),
)

/**
 * The reminder "Sound" rows open HyperOS's ringtone picker, which saves the choice as the phone's calendar sound
 * itself; reading its default from HyperOS's framework resources crashed Calendar off HyperOS. Without that picker the
 * rows open Android's notification settings for Calendar (reminder sounds) or the phone's sound settings (alarm sound).
 */
private val ringtonePickerPatch = bytecodePatch(
    description = "Opens Android's sound settings from the reminder sound rows.",
) {
    extendWith(XIAOMI_CALENDAR_EXTENSION)

    execute {
        RingtonePickerFingerprint.method.apply {
            val superclass = RingtonePickerFingerprint.classDef.superclass
                ?: throw PatchException("ringtone preference has no superclass")
            if (implementation!!.registerCount - 2 < 1) throw PatchException("ringtone picker intent has no local register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, Lorg/ungoogled/xiaomicalendar/Ringtones;->hasMiuiPicker()Z
                    move-result v0
                    if-nez v0, :miui_picker
                    invoke-super { p0, p1 }, $superclass->$name(Landroid/content/Intent;)V
                    invoke-static { p1 }, Lorg/ungoogled/xiaomicalendar/Ringtones;->toSettings(Landroid/content/Intent;)V
                    return-void
                """,
                ExternalLabel("miui_picker", implementation!!.instructions.first()),
            )
        }
    }
}

private const val RETROFIT = "Lcom/android/calendar/common/retrofit/RetrofitGenerator;"
private const val NOTICE_INTERCEPTOR = "Lcom/android/calendar/common/retrofit/RetrofitGenerator\$CheckUserNoticeAgreedInterceptor;"
private const val CONSENT = "Lorg/ungoogled/xiaomicalendar/Consent;"

/** UserNoticeUtil.isUserNoticeAgreed(Context): true at once on a Global build, else the user's accepted notice. */
private object UserNoticeFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("isUserNoticeAgreed IS_INTERNATIONAL"),
)

/** The check in front of every request of Calendar's server client (RetrofitGenerator). */
private object NoticeInterceptorFingerprint : Fingerprint(
    definingClass = NOTICE_INTERCEPTOR,
    name = "intercept",
)

/** RetrofitGenerator.getEncryptionClient(): the second server client, built without that check. */
private object EncryptionClientFingerprint : Fingerprint(
    definingClass = RETROFIT,
    name = "getEncryptionClient",
)

/** RetrofitGenerator.getClient(): the client built with the check. */
private object ServerClientFingerprint : Fingerprint(
    definingClass = RETROFIT,
    name = "getClient",
)

/** MiPushManager.init(Context): registers with Xiaomi's push service. */
private object PushInitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("mid night do not init()"),
)

/** OneTrackHelper.initialize(Context): Xiaomi's usage statistics. */
private object OneTrackInitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("Cal:D:OneTrackHelper", "initialize()"),
)

/**
 * Calendar talks to Xiaomi's servers (daily pictures, holidays, cards, push, statistics) only once the user notice is
 * accepted -- which the stand-ins' Global build answered at once, so it went online without asking. Calendar's own
 * flag for the accepted notice now decides: its server client refuses requests without it (both clients now carry
 * that check), push registers only with it, the notice is offered where Calendar offers it, and the home screen asks
 * once on first launch (Consent). Xiaomi's usage statistics (OneTrack) stay off either way. Everything local works
 * without it; the user notice flag that Calendar's other checks read stays answered, so they keep working.
 */
private val serverConsentPatch = bytecodePatch(
    description = "Asks before Calendar talks to Xiaomi's servers.",
) {
    extendWith(XIAOMI_CALENDAR_EXTENSION)

    execute {
        // The notice check before every request.
        NoticeInterceptorFingerprint.method.apply {
            val index = implementation!!.instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_STATIC && ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                    r.parameterTypes.isEmpty() && r.returnType == "Z"
                }
            }
            if (index < 0) throw PatchException("user notice check not found in the server client's interceptor")
            replaceInstruction(index, "invoke-static { }, $CONSENT->network()Z")
        }

        // The second client gets the same check, right after its builder is made.
        val clientInstructions = ServerClientFingerprint.method.implementation!!.instructions.toList()
        val made = clientInstructions.indexOfFirst {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == NOTICE_INTERCEPTOR
        }
        if (made < 0) throw PatchException("server client's notice check not found")
        val check = (clientInstructions[made] as OneRegisterInstruction).registerA
        val addInterceptor = clientInstructions.drop(made + 1).firstNotNullOfOrNull { insn ->
            val call = insn as? FiveRegisterInstruction
            if (insn.opcode == Opcode.INVOKE_VIRTUAL && call!!.registerCount == 2 && call.registerD == check) {
                (insn as ReferenceInstruction).reference as MethodReference
            } else {
                null
            }
        } ?: throw PatchException("server client's addInterceptor not found")
        val builder = addInterceptor.definingClass
        EncryptionClientFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val init = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_DIRECT && ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                    r.definingClass == builder && r.name == "<init>" && r.parameterTypes.isEmpty()
                }
            }
            if (init < 0) throw PatchException("encryption client's builder not found")
            val builderRegister = (instructions[init] as FiveRegisterInstruction).registerC
            // The next instruction only writes a register (the "staging server" flag): free before it.
            val next = instructions[init + 1]
            if (next.opcode != Opcode.SGET_BOOLEAN) throw PatchException("unexpected ${next.opcode} after the encryption client's builder")
            val scratch = (next as OneRegisterInstruction).registerA
            if (scratch == builderRegister) throw PatchException("no free register in getEncryptionClient")
            addInstructions(
                init + 1,
                """
                    new-instance v$scratch, $NOTICE_INTERCEPTOR
                    invoke-direct { v$scratch }, $NOTICE_INTERCEPTOR-><init>()V
                    invoke-virtual { v$builderRegister, v$scratch }, $addInterceptor
                """,
            )
        }

        // Calendar's "offer the user notice" check (UserNoticeUtil, next to isUserNoticeAgreed).
        val noticeUtil = mutableClassDefBy(UserNoticeFingerprint.classDef.type)
        val offer = noticeUtil.methods.singleOrNull {
            it.returnType == "Z" && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;", "Z")
        } ?: throw PatchException("user notice offer check not found")
        if (offer.implementation!!.registerCount - 2 < 1) throw PatchException("user notice offer check has no local register")
        offer.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $CONSENT->offer(Landroid/content/Context;Z)Z
                move-result v0
                return v0
            """,
        )

        // Push registration.
        PushInitFingerprint.method.apply {
            if (implementation!!.registerCount - 1 < 1) throw PatchException("push init has no local register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $CONSENT->network()Z
                    move-result v0
                    if-nez v0, :servers_allowed
                    return-void
                """,
                ExternalLabel("servers_allowed", implementation!!.instructions.first()),
            )
        }

        // Usage statistics: never.
        OneTrackInitFingerprint.method.addInstructions(0, "return-void")
    }
}

private const val PERMISSION_EDITOR = "miui.intent.action.APP_PERM_EDITOR"
private const val INTENT_INIT = "Landroid/content/Intent;-><init>(Ljava/lang/String;)V"

/**
 * Calendar's permission settings open HyperOS's permission editor (the Security app), which nothing answers elsewhere:
 * Calendar crashed (ActivityNotFoundException). In the classes that name it, every Intent made from an action goes
 * through Intents.fit, which turns that one into Android's App info for Calendar when nothing answers it.
 */
private val permissionEditorPatch = bytecodePatch(
    description = "Opens Android's App info instead of HyperOS's permission editor.",
) {
    extendWith(XIAOMI_CALENDAR_EXTENSION)

    execute {
        fun Method.namesEditor() = implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == PERMISSION_EDITOR
        } == true

        val types = mutableListOf<String>()
        classDefForEach { c ->
            if (!c.type.startsWith("Lorg/ungoogled/") && c.methods.any { it.namesEditor() }) types += c.type
        }
        var fitted = 0
        for (type in types) {
            for (method in mutableClassDefBy(type).methods) {
                val instructions = method.implementation?.instructions ?: continue
                val sites = instructions.withIndex().filter { (_, insn) ->
                    insn.opcode == Opcode.INVOKE_DIRECT &&
                        ((insn as ReferenceInstruction).reference as MethodReference).toString() == INTENT_INIT
                }.map { it.index }.reversed()
                for (index in sites) {
                    val intent = (instructions[index] as FiveRegisterInstruction).registerC
                    method.addInstructions(
                        index + 1,
                        "invoke-static/range { v$intent .. v$intent }, Lorg/ungoogled/xiaomicalendar/Intents;->fit(Landroid/content/Intent;)V",
                    )
                    fitted++
                }
            }
        }
        log.info("Permission editor intents: $fitted in $types")
        // Measured against 18.0.2.0: UserNoticeUtil, the privacy settings, the ICS import and a permission helper.
        if (types.size < 3 || fitted < 3) throw PatchException("permission editor not found ($fitted intents in $types)")
    }
}

/**
 * Off HyperOS Calendar dies at once on miui.os.Build (a HyperOS class), then can neither read nor save events on
 * Android's calendar provider. The extension carries stand-ins for the HyperOS classes it uses (generated by
 * .github/scripts/xiaomi-standins with gen.py's default --only plus `|android/media/ExtraRingtone|android/provider/
 * SystemSettings`); on a Xiaomi phone the framework's own classes load first, so they never run there. MIUI's request
 * coder (miui.util.CoderUtils: SHA-1, AES) is answered for real, so Xiaomi's servers accept Calendar's signed requests
 * (the Day view's desk pictures). Xiaomi account / MiCloud sync and the AI features stay off.
 */
@Suppress("unused")
val enableNonHyperOsPatch = bytecodePatch(
    name = "Enable non-HyperOS ROM support",
    description = "Makes Xiaomi Calendar usable on non-Xiaomi ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_XIAOMI_CALENDAR)
    dependsOn(
        miuiMembersPatch, calendarProviderPatch, permissionsPatch, serverConsentPatch, ringtonePickerPatch, permissionEditorPatch,
    )
    extendWith(XIAOMI_CALENDAR_EXTENSION)
}
