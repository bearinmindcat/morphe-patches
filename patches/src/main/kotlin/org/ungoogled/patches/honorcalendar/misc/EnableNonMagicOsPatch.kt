package org.ungoogled.patches.honorcalendar.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.honor.MAGIC_DAY_NIGHT
import org.ungoogled.patches.honor.MAGIC_DAY_NIGHT_NO_ACTION_BAR
import org.ungoogled.patches.honor.convertMagicInterpolators
import org.ungoogled.patches.honor.followMagicDayNight
import org.ungoogled.patches.honor.replaceMagicFrameworkLiterals
import org.ungoogled.patches.honor.replaceMagicFrameworkReferences
import org.ungoogled.patches.honorcalendar.HonorCalendarConstants.COMPATIBILITY_HONOR_CALENDAR
import org.ungoogled.patches.samsung.redirectCalls

internal const val HONOR_CALENDAR_EXTENSION = "extensions/honorcalendar.mpe"

private val log = java.util.logging.Logger.getLogger("HonorCalendar")

/**
 * MagicOS framework references in Calendar's resources that have no plain value: the app's own copies of MagicOS's
 * colours (day and night) where it carries them, else Android's nearest answer.
 */
private val MAGIC_EXTRA = mapOf(
    "?0x02010060" to "?android:attr/listChoiceIndicatorMultiple", // attr/listChoiceIndicatorMultipleIgnore
    "?0x020100b7" to "?android:attr/textColorPrimary", // attr/colorToolbarText
    "?0x020100ef" to "sans-serif", // attr/textFontFamilyRegular (MagicOS: "sans-serif")
    "?0x020100f0" to "sans-serif-medium", // attr/textFontFamilyMedium (MagicOS: its own "HnChinese-medium")
    "?0x020100fd" to "@color/magic_color_text_tertiary", // attr/colorTertiary
    "?0x02010103" to "@color/magic_functional_blue", // attr/textColorPrimaryActivated
    "@0x020300ab" to "@android:drawable/edit_text", // drawable/edit_text_linear_magic
    "@0x020500cd" to "@color/magic_color_divider_horizontal", // color/divider_horizontal_color_magic
    "@0x02060008" to "@style/$MAGIC_DAY_NIGHT", // style/Theme.Magic
    "@0x02060009" to "@style/$MAGIC_DAY_NIGHT_NO_ACTION_BAR", // style/Theme.Magic.NoActionBar
    "@0x020600ce" to "@android:style/Theme.DeviceDefault.NoActionBar", // style/Theme.Magic.Dark.NoActionBar
    "@0x02060102" to "@android:style/Widget.DeviceDefault.Button.Borderless", // style/Widget.Magic.Button.Borderless
    "@0x0206019f" to "@android:style/Widget.Material.SearchView", // style/Widget.Magic.SearchView
)

/**
 * Calendar's styles take margins, text sizes and fonts from MagicOS's theme (see replaceMagicFrameworkReferences), and
 * its screens follow dark mode through MagicOS's theme (see followMagicDayNight).
 */
private val magicResourcesPatch = resourcePatch(
    description = "Gives Honor Calendar the sizes and day/night theme MagicOS would.",
) {
    execute {
        val replaced = replaceMagicFrameworkReferences(MAGIC_EXTRA)
        log.info("MagicOS framework references: $replaced")
        if (replaced.size < 20) throw PatchException("only ${replaced.size} MagicOS framework references found")

        val (activities, styles) = followMagicDayNight()
        log.info("Day/night themes: $activities activities, $styles styles")
        if (activities < 5) throw PatchException("themes changed: $activities activities, $styles styles")
        // MagicOS's accent (Theme.Magic's colorAccent; its textColorPrimaryActivated, which Calendar's code reads as
        // Android's colorAccent off MagicOS: see magicLiteralsPatch) in the day/night theme, from the app's own day/night
        // copy of it.
        document("res/values/styles.xml").use { xml ->
            val style = (0 until xml.documentElement.childNodes.length).map { xml.documentElement.childNodes.item(it) }
                .filterIsInstance<org.w3c.dom.Element>().firstOrNull { it.getAttribute("name") == MAGIC_DAY_NIGHT }
                ?: throw PatchException("$MAGIC_DAY_NIGHT style not found")
            style.appendChild(xml.createElement("item").apply {
                setAttribute("name", "android:colorAccent"); textContent = "@color/magic_accent"
            })
        }
        // Calendar's icons are tinted with ?android:colorPrimary, which is Theme.Magic's icon grey but a pale surface
        // colour in Android's day/night theme (the event screen's edit pencil was invisible): the app's copy of
        // Theme.Magic's value instead.
        var icons = 0
        for (path in listDecodableFiles("res/drawable*/*.xml")) {
            val file = get(path)
            if (!file.isFile) continue
            val text = file.readText()
            val changed = text.replace(Regex("""\?android:(attr/)?colorPrimary"""), "@color/magic_selector_color_primary")
            if (changed != text) {
                file.writeText(changed)
                icons++
            }
        }
        log.info("Icons tinted with colorPrimary: $icons")
        // Measured: 33 drawables.
        if (icons < 20) throw PatchException("only $icons icons tinted with colorPrimary found")

        // Reminders: Android's calendar provider broadcasts EVENT_REMINDER without the permission MagicOS's provider holds
        // and AlertReceiver demands (com.hihonor.calendar.permissons.ALERT_RECEIVER), so no reminder ever arrived.
        document("AndroidManifest.xml").use { manifest ->
            val receivers = manifest.getElementsByTagName("receiver")
            val alerts = (0 until receivers.length).map { receivers.item(it) as org.w3c.dom.Element }
                .singleOrNull { it.getAttribute("android:name") == "com.android.calendar.alerts.AlertReceiver" }
                ?: throw PatchException("AlertReceiver not found")
            alerts.removeAttribute("android:permission")
            // The time zone list standing in for MagicOS Settings' zone picker (see intentsPatch).
            val application = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            application.appendChild(manifest.createElement("activity").apply {
                setAttribute("android:name", "org.ungoogled.honorcalendar.ZonePicker")
                setAttribute("android:exported", "false")
                setAttribute("android:theme", "@style/$MAGIC_DAY_NIGHT")
            })
        }

        val interpolators = convertMagicInterpolators()
        log.info("MagicOS interpolators: $interpolators")
        // Measured: the four res/interpolator/cubic_bezier_interpolator_type_*.xml.
        if (interpolators < 4) throw PatchException("only $interpolators MagicOS interpolators found")
    }
}

/**
 * Calendar's code names MagicOS framework resources by id too (resolveAttribute of MagicOS margins, text sizes,
 * corner radii and accent; Theme.Magic for its widgets; two interpolators; the back arrow). Each gets the app's own
 * copy of what MagicOS gives (Honor's UI kit in the app ships them), the accent becomes Android's colorAccent.
 */
private val magicLiteralsPatch = bytecodePatch(
    description = "Answers MagicOS resource ids in Honor Calendar's code.",
) {
    dependsOn(magicResourcesPatch)
    extendWith(HONOR_CALENDAR_EXTENSION)

    execute {
        fun id(type: ResourceType, name: String) = resourceId(type, name).toInt()
        val answers = mapOf(
            0x020100c8 to id(ResourceType.DIMEN, "magic_dimens_max_start"), // attr/maxPaddingStart
            0x020100ca to id(ResourceType.DIMEN, "magic_dimens_max_end"), // attr/maxPaddingEnd
            0x020100e9 to id(ResourceType.DIMEN, "magic_text_size_body2"), // attr/textSizeBody2
            0x020100ef to id(ResourceType.STRING, "magic_text_font_family_regular"), // attr/textFontFamilyRegular
            0x020100f3 to id(ResourceType.DIMEN, "magic_corner_radius_large"), // attr/defaultCornerRadiusL
            0x020100f4 to id(ResourceType.DIMEN, "magic_corner_radius_mediums"), // attr/defaultCornerRadiusM
            0x02010103 to 0x01010435, // attr/textColorPrimaryActivated -> android:attr/colorAccent
            0x02030026 to id(ResourceType.DRAWABLE, "ic_public_back"), // drawable/ic_public_back
            0x02060008 to id(ResourceType.STYLE, "Theme.Magic.DataPrivacyActivity"), // style/Theme.Magic (-> MagicDayNight)
            0x02060009 to id(ResourceType.STYLE, "Theme.Calender.AllInOneActivity"), // style/Theme.Magic.NoActionBar
            // interpolator/cubic_bezier_interpolator_type_33_33 and _20_80: the app's copies (same curves).
            0x02080004 to id(ResourceType.ANIM, "hnanimation_cubic_bezier_interpolator_type_33_33"),
            0x020800ad to id(ResourceType.ANIM, "hwsubtab_cubic_bezier_interpolator_type_20_80"),
        )
        val (replaced, moved) = replaceMagicFrameworkLiterals(answers, "Lorg/ungoogled/honorcalendar/Magic;")
        log.info("MagicOS ids in code: $replaced, resolveAttribute calls: $moved")
        // Measured against 13.3.0.345: 44 literals of 12 ids.
        if (replaced.size < 12) throw PatchException("only ${replaced.size} MagicOS ids found in code")
    }
}

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

/**
 * Calendar reads and writes "com.android.calendar" as MagicOS's calendar provider: columns Android's lacks, and a phone
 * calendar the provider makes. Off MagicOS the month view died on its first query ("Invalid column sync_data2") and
 * there was no calendar to save to. Its provider calls go through Calendars, which fits them to Android's.
 */
private val calendarProviderPatch = bytecodePatch(
    description = "Fits Calendar's calls to MagicOS's calendar provider to Android's.",
) {
    extendWith(HONOR_CALENDAR_EXTENSION)

    execute {
        val moved = redirectCalls("Lorg/ungoogled/honorcalendar/Calendars;", PROVIDER_CALLS)
        log.info("Provider calls: $moved")
        if (moved < 100) throw PatchException("only $moved provider calls found")
    }
}

/**
 * Makes the app's subclasses of [from] extend [to] instead (a subclass of it with the same constructors), their
 * constructors' super calls included. Returns the subclasses and constructor calls moved.
 */
private fun app.morphe.patcher.patch.BytecodePatchContext.reparent(from: String, to: String): Pair<Int, Int> {
    val subclasses = mutableListOf<String>()
    classDefForEach { c ->
        if (!c.type.startsWith("Lorg/ungoogled/") && c.superclass == from) subclasses += c.type
    }
    var moved = 0
    for (type in subclasses) {
        val mutable = mutableClassDefBy(type)
        mutable.setSuperClass(to)
        for (method in mutable.methods.filter { it.name == "<init>" }) {
            val instructions = method.implementation?.instructions ?: continue
            for (index in instructions.indices) {
                val insn = instructions[index]
                val r = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (r.definingClass != from || r.name != "<init>") continue
                val call = when (insn) {
                    is FiveRegisterInstruction -> listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                        .take(insn.registerCount).joinToString(", ", "invoke-direct { ", " }") { "v$it" }
                    is RegisterRangeInstruction ->
                        "invoke-direct/range { v${insn.startRegister} .. v${insn.startRegister + insn.registerCount - 1} }"
                    else -> throw PatchException("unexpected ${insn.opcode} in $type")
                }
                method.replaceInstruction(index, "$call, $to-><init>(${r.parameterTypes.joinToString("")})V")
                moved++
            }
        }
    }
    return subclasses.size to moved
}

/**
 * Calendar's AsyncQueryHandler subclasses (the calendar list of the edit screen, event details, time zones) and its
 * CursorLoader subclass (the home screen widget's events) make their provider calls inside Android's classes, out of
 * reach of calendarProviderPatch: the edit screen listed the phone calendar under Android's account type and saved
 * new events to another calendar, the widget died on "Invalid column sync_data2". They now extend Queries / Loader,
 * the same classes with those calls going through Calendars; EventDays.query (Android's helper) goes there too.
 */
private val frameworkQueriesPatch = bytecodePatch(
    description = "Sends Calendar's background queries through the provider fitting.",
) {
    extendWith(HONOR_CALENDAR_EXTENSION)

    execute {
        val queries = reparent("Landroid/content/AsyncQueryHandler;", "Lorg/ungoogled/honorcalendar/Queries;")
        val loaders = reparent("Landroid/content/CursorLoader;", "Lorg/ungoogled/honorcalendar/Loader;")
        val eventDays = redirectCalls(
            "Lorg/ungoogled/honorcalendar/Calendars;",
            mapOf("Landroid/provider/CalendarContract\$EventDays;" to setOf("query(Landroid/content/ContentResolver;II[Ljava/lang/String;)Landroid/database/Cursor;")),
        )
        log.info("AsyncQueryHandler subclasses / constructors: $queries, CursorLoader: $loaders, EventDays.query: $eventDays")
        // Measured against 13.3.0.345: 6 AsyncQueryHandler subclasses, 1 CursorLoader subclass, 1 EventDays.query.
        if (queries.first < 6 || queries.second < queries.first || loaders.first < 1 || loaders.second < 1 || eventDays < 1) {
            throw PatchException("framework queries: $queries, $loaders, $eventDays")
        }
    }
}

/** The home screen's onResume. */
private object HomeResumeFingerprint : Fingerprint(
    definingClass = "Lcom/android/calendar/AllInOneActivity;",
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

/** Offers Android's notification settings, which reminders need (see Notifications). */
private val notificationPermissionPatch = bytecodePatch(
    description = "Offers the notification settings reminders need.",
) {
    extendWith(HONOR_CALENDAR_EXTENSION)

    execute {
        HomeResumeFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, Lorg/ungoogled/honorcalendar/Notifications;->ask(Landroid/app/Activity;)V",
        )
    }
}

/** Utils.safeStartActivity(Context, Intent, String): Calendar's way to open other apps' screens. */
private object SafeStartActivityFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;", "Ljava/lang/String;"),
    strings = listOf("utils safeStartActivity ActivityNotFoundException"),
)

/** ZonePickerUtils.createZonePickerIntent: MagicOS Settings' time zone picker. */
private object ZonePickerIntentFingerprint : Fingerprint(
    returnType = "Landroid/content/Intent;",
    strings = listOf("hihonor.intent.action.ZONE_PICKER"),
)

/** ZonePickerUtils.isPermissionHNSOSIsOk: the picker opens only with MagicOS's system signature permission. */
private object ZonePickerPermissionFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/app/Activity;"),
    strings = listOf("hihonor.android.permission.HW_SIGNATURE_OR_SYSTEM"),
)

/**
 * Two MagicOS screens Calendar opens have no counterpart elsewhere: the reminder settings' "Notifications" row (MagicOS's
 * notification centre) did nothing, and the time zone rows of the event editor and settings (MagicOS Settings' zone
 * picker, opened only with MagicOS's system signature permission) did nothing. They now open Android's notification
 * settings for Calendar and a time zone list (Intents, ZonePicker).
 */
private val intentsPatch = bytecodePatch(
    description = "Opens Android's notification settings and a time zone list.",
) {
    extendWith(HONOR_CALENDAR_EXTENSION)

    execute {
        SafeStartActivityFingerprint.method.addInstructions(
            0,
            "invoke-static { p0, p1 }, Lorg/ungoogled/honorcalendar/Intents;->fit(Landroid/content/Context;Landroid/content/Intent;)V",
        )
        ZonePickerIntentFingerprint.method.apply {
            val returns = implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }.reversed()
            if (returns.isEmpty()) throw PatchException("zone picker intent has no return")
            for (index in returns) {
                val register = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, Lorg/ungoogled/honorcalendar/Intents;->fit(Landroid/content/Intent;)Landroid/content/Intent;
                        move-result-object v$register
                    """,
                )
            }
        }
        ZonePickerPermissionFingerprint.method.apply {
            val index = implementation!!.instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "hihonor.android.permission.HW_SIGNATURE_OR_SYSTEM"
            }
            val register = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
            // A permission the picker's caller has already checked it holds.
            replaceInstruction(index, "const-string v$register, \"android.permission.READ_CALENDAR\"")
        }
    }
}

/**
 * Off MagicOS Calendar dies at once on com.hihonor.android.os.SystemPropertiesEx (a MagicOS framework class), its layouts
 * then on MagicOS's theme attributes, its code on MagicOS resource ids, and it cannot use Android's calendar provider.
 * The extension carries stand-ins for the 59 MagicOS classes it uses (generated by .github/scripts/honor-standins; the
 * toolbar's cancel/done icons are MagicToolbar), the provider fitting (Calendars, Queries, Loader) and the screens
 * MagicOS would open (ZonePicker, Android's notification settings). On an Honor phone the framework's own classes load
 * first. Honor account sync, birthdays from Honor's contacts provider, holiday downloads (they stay behind the app's own
 * "Allow Internet access?" question), MagicOS's AI and the clock-alarm reminders stay off.
 */
@Suppress("unused")
val enableNonMagicOsPatch = bytecodePatch(
    name = "Enable non-MagicOS ROM support",
    description = "Makes Honor Calendar usable on non-Honor ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HONOR_CALENDAR)
    dependsOn(
        magicResourcesPatch, magicLiteralsPatch, calendarProviderPatch, frameworkQueriesPatch, notificationPermissionPatch,
        intentsPatch,
    )
    extendWith(HONOR_CALENDAR_EXTENSION)
}
