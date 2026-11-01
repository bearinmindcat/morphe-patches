package org.ungoogled.patches.honorclock.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.honor.followMagicDayNight
import org.ungoogled.patches.honor.redirectMagicFrameworkLookups
import org.ungoogled.patches.honor.replaceMagicIconDrawables
import org.ungoogled.patches.honor.replaceMagicFrameworkIds
import org.ungoogled.patches.honor.replaceMagicInterpolators
import org.ungoogled.patches.honor.replaceMagicFrameworkReferences
import org.ungoogled.patches.honorclock.HonorClockConstants.COMPATIBILITY_HONOR_CLOCK
import org.ungoogled.patches.samsung.redirectCalls
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val HONOR_CLOCK_EXTENSION = "extensions/honorclock.mpe"
private const val EXT = "Lorg/ungoogled/honorclock"
private const val EXT_CLASSES = "org.ungoogled.honorclock"
private const val ZONE_PICKER = "hihonor.intent.action.ZONE_PICKER"

private val log = java.util.logging.Logger.getLogger("HonorClock")

/**
 * The notification and game-mode icons are MagicOS framework icon drawables (see replaceMagicIconDrawables): their
 * framework icon sources, and the app's copies of them.
 */
private val CLOCK_ICON_SOURCES = mapOf(
    "@0x020307a8" to "icsvg_public_clock_filled", // ic_notify_alarm
    "@0x0203038a" to "icsvg_hnclock_timer_filled", // ic_notify_timer
    "@0x020307a7" to "icsvg_public_clock", // ic_clock_game
)

/**
 * Layouts another process inflates (RemoteViews: the timer's notification, the game-mode clock): there only Android's
 * own widgets load, so MagicOS's framework TextView/ImageView names become Android's (the timer notification was
 * refused, "Couldn't inflate contentViews", and the system killed Clock when a timer finished).
 */
private val REMOTE_LAYOUTS = listOf("chronometer_notif_content", "game_space_clock")
private val FRAMEWORK_WIDGET_TAG = Regex("""(</?)com\.hihonor\.android\.widget\.(?:Hw)?(TextView|ImageView)\b""")

/**
 * What MagicOS's theme answers for Clock's references without a plain value: colours with night variants and styles,
 * from the app's own copies (its bundled Honor UI kit has the same values and night variants), Android's day/night
 * and dark themes for MagicOS's, Android's medium font for MagicOS's own.
 */
private val CLOCK_ANSWERS = mapOf(
    "?0x02010074" to "@color/magic_clickeffic_default_color", // attr/clickEffectColor
    "?0x020100c3" to "@color/magic_color_8_600", // attr/magicColor8
    "?0x020100ef" to "sans-serif", // attr/textFontFamilyRegular
    "?0x020100f0" to "sans-serif-medium", // attr/textFontFamilyMedium ("HnChinese-medium" on MagicOS)
    "?0x02010101" to "@dimen/magic_tips_bg_alpha", // attr/tipsBackgroundAlpha
    "@0x02030033" to "@drawable/arrow_right", // drawable/ic_public_arrow_right
    "@0x02030827" to "@drawable/icsvg_public_more12", // drawable/icsvg_public_more12 (overflow button)
    "@0x020500cd" to "@color/magic_color_divider_horizontal", // color/divider_horizontal_color_magic
    "@0x02050177" to "@color/magic_color_fg", // color/magic_primary
    "@0x02050178" to "#ffffffff", // color/magic_white (the app's magic_white has its own night value)
    "@0x02050180" to "@color/magic_color_8_600", // color/magic_color_8
    "@0x02050186" to "@color/magic_gary_2", // color/magic_gray_2
    "@0x0205019b" to "@color/magic_color_8_600", // color/magic_functional_red
    "@0x020501a1" to "@color/magic_gray_50", // color/magic_color_gray_1
    "@0x02060008" to "@android:style/Theme.DeviceDefault.DayNight", // style/Theme.Magic (a widget's theme)
    "@0x02060009" to "@style/MagicDayNight.NoActionBar", // style/Theme.Magic.NoActionBar (parent of app themes)
    "@0x020600ce" to "@android:style/Theme.DeviceDefault.NoActionBar", // style/Theme.Magic.Dark.NoActionBar (alarm screens)
    "@0x020600d6" to "@android:style/Widget.DeviceDefault.ActionButton.Overflow", // style/Widget.Magic.ActionButton.Overflow
    "@0x020600e1" to "@style/Widget.Magic.HwEditText.Linear", // style/Widget.Magic.EditText.Linear
    "@0x02100066" to "@android:id/text2", // id/detail (a preference's value)
    "@0x021000d9" to "@id/magic_card_effect_view_id", // id/magic_card_effect_view_id
)

/** Clock's code names three MagicOS framework resources by id. */
private val CLOCK_CODE_ANSWERS = mapOf(
    0x02030022 to "", // drawable/cover_background_default: smart-cover window only, never shown without MagicOS
    0x02100066 to "0x01020015", // id/detail -> android:id/text2, as in the layouts
    0x021000d9 to "id/magic_card_effect_view_id",
)

private val magicResourcesPatch = resourcePatch(
    description = "Gives Honor Clock the sizes, fonts, colours, icons and day/night theme MagicOS would.",
) {
    execute {
        val icons = replaceMagicIconDrawables(CLOCK_ICON_SOURCES)
        log.info("MagicOS icon drawables: $icons")
        if (icons != 3) throw PatchException("$icons MagicOS icon drawables found")

        for (name in REMOTE_LAYOUTS) {
            val file = get("res/layout/$name.xml")
            val text = if (file.isFile) file.readText() else ""
            val changed = FRAMEWORK_WIDGET_TAG.replace(text) { it.groupValues[1] + it.groupValues[2] }
            if (changed == text) throw PatchException("layout/$name has no MagicOS framework widget")
            file.writeText(changed)
        }

        val interpolators = replaceMagicInterpolators()
        log.info("MagicOS interpolators: $interpolators")
        if (interpolators < 2) throw PatchException("only $interpolators MagicOS interpolators found")

        val replaced = replaceMagicFrameworkReferences(CLOCK_ANSWERS)
        log.info("MagicOS framework references: $replaced")
        if (replaced.size < 60) throw PatchException("only ${replaced.size} MagicOS framework references found")

        val (activities, styles) = followMagicDayNight()
        log.info("Day/night themes: $activities activities, $styles styles")

        // The world clock's city picker (see CityPicker): MagicOS's is in its Settings app.
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0)
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", "$EXT_CLASSES.CityPicker")
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:theme", "@style/MagicDayNight")
            val filter = manifest.createElement("intent-filter")
            for (action in listOf(ZONE_PICKER, "${ZONE_PICKER}_CARD")) {
                filter.appendChild(manifest.createElement("action").apply { setAttribute("android:name", action) })
            }
            filter.appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.DEFAULT") })
            activity.appendChild(filter)
            application.appendChild(activity)

            // Clock's home-screen widgets are drawn by its own views (clock faces, cards), which only MagicOS's
            // launcher inflates; any other launcher refuses classes that are not Android's RemoteViews widgets
            // ("Can't load widget"). Off the widget list rather than broken on the home screen.
            val receivers = manifest.getElementsByTagName("receiver")
            var widgets = 0
            for (i in 0 until receivers.length) {
                val receiver = receivers.item(i) as org.w3c.dom.Element
                val metas = receiver.getElementsByTagName("meta-data")
                val provider = (0 until metas.length).any {
                    (metas.item(it) as org.w3c.dom.Element).getAttribute("android:name") == "android.appwidget.provider"
                }
                if (!provider) continue
                receiver.setAttribute("android:enabled", "false")
                widgets++
            }
            log.info("Home-screen widgets turned off: $widgets")
            // Measured against 12.0.1.196: digital, analog, dual (world) clock, Muslim, medium and small card.
            if (widgets != 6) throw PatchException("$widgets widget providers found")
        }
    }
}

private const val MAGIC_LIST_VIEW = "Lcom/hihonor/android/widget/ListView;"

/**
 * A PreferenceFragment's list is MagicOS's ListView only on MagicOS (its framework layout names it); elsewhere it is
 * Android's, and the alarm editor's cast of it to MagicOS's ListView failed (ClassCastException when opening it). In the
 * app's preference fragments the list is treated as Android's ListView (the methods called on it are Android's).
 * Returns the instructions changed.
 */
private fun BytecodePatchContext.preferenceListsAsAndroid(): Int {
    val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
    classDefForEach { c ->
        if (c.superclass != "Landroid/preference/PreferenceFragment;") return@classDefForEach
        for (m in c.methods) {
            val uses = m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type == MAGIC_LIST_VIEW ||
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == MAGIC_LIST_VIEW
            } ?: false
            if (uses) sites += c.type to m
        }
    }
    var changed = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            val ref = (insn as? ReferenceInstruction)?.reference
            val smali = when {
                ref is TypeReference && ref.type == MAGIC_LIST_VIEW && insn.opcode == Opcode.CHECK_CAST ->
                    "check-cast v${(insn as OneRegisterInstruction).registerA}, Landroid/widget/ListView;"
                ref is TypeReference && ref.type == MAGIC_LIST_VIEW && insn.opcode == Opcode.INSTANCE_OF ->
                    (insn as TwoRegisterInstruction).let { "instance-of v${it.registerA}, v${it.registerB}, Landroid/widget/ListView;" }
                ref is MethodReference && ref.definingClass == MAGIC_LIST_VIEW && insn.opcode == Opcode.INVOKE_VIRTUAL ->
                    (insn as FiveRegisterInstruction).let { i ->
                        listOf(i.registerC, i.registerD, i.registerE, i.registerF, i.registerG).take(i.registerCount)
                            .joinToString(", ", "invoke-virtual { ", " }, ") { "v$it" } +
                            "Landroid/widget/ListView;->${ref.name}(${ref.parameterTypes.joinToString("")})${ref.returnType}"
                    }
                ref is TypeReference && ref.type == MAGIC_LIST_VIEW || ref is MethodReference && ref.definingClass == MAGIC_LIST_VIEW ->
                    throw PatchException("unexpected ${insn.opcode} on MagicOS's ListView in $type.${method.name}")
                else -> continue
            }
            mutable.replaceInstruction(index, smali)
            changed++
        }
    }
    return changed
}

/**
 * The timer's default sound is MagicOS's Timer_Beep.ogg, looked up in the phone's sounds; where there is none the
 * timer (and its page's sound setting) fell back to "silent" and finished without a sound. It falls back to the
 * phone's alarm sound instead.
 * Returns the fallbacks changed.
 */
private fun BytecodePatchContext.timerSoundFallback(): Int {
    fun strings(m: com.android.tools.smali.dexlib2.iface.Method) =
        m.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
    val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
    classDefForEach { c ->
        for (m in c.methods) if (strings(m).let { "Timer_Beep.ogg" in it && "silent" in it }) sites += c.type to m
    }
    var changed = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            if (insn.opcode != Opcode.CONST_STRING || ((insn as ReferenceInstruction).reference as StringReference).string != "silent") continue
            mutable.replaceInstruction(index, "const-string v${(insn as OneRegisterInstruction).registerA}, \"content://settings/system/alarm_alert\"")
            changed++
        }
    }
    return changed
}

/**
 * "+" on the world clock opens MagicOS Settings' city picker (`hihonor.intent.action.ZONE_PICKER` in
 * com.android.settings), which nothing else has: the picker is looked for in Clock itself (CityPicker). Returns the
 * places changed.
 */
private fun BytecodePatchContext.cityPickerInApp(): Int {
    fun strings(m: com.android.tools.smali.dexlib2.iface.Method) =
        m.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
    val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
    classDefForEach { c ->
        for (m in c.methods) if (strings(m).let { ZONE_PICKER in it && "com.android.settings" in it }) sites += c.type to m
    }
    var changed = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            if (insn.opcode != Opcode.CONST_STRING || ((insn as ReferenceInstruction).reference as StringReference).string != "com.android.settings") continue
            mutable.replaceInstruction(index, "const-string v${(insn as OneRegisterInstruction).registerA}, \"${COMPATIBILITY_HONOR_CLOCK.packageName}\"")
            changed++
        }
    }
    return changed
}

/**
 * The ringtone picker Clock asks for is MagicOS's: every RINGTONE_PICKER intent names its package (`y.P()`: the Themes
 * app, or Settings in China) and its category (`y.F()`: HWRING / HNRING). Without one Clock fell back to a dialog
 * whose picks it never reads (requests 14/15 are not handled), so a sound could not be chosen. The package is now none
 * and the category Android's DEFAULT, so Android's own ringtone picker opens and Clock's normal result path takes the
 * pick. Returns the methods changed.
 */
private fun BytecodePatchContext.androidRingtonePicker(): Int {
    fun strings(m: com.android.tools.smali.dexlib2.iface.Method) =
        m.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
    // method's strings -> what it answers now
    val answers = listOf(
        setOf("com.hihonor.android.thememanager", "com.android.settings") to "const/4 v0, 0x0",
        setOf("android.intent.category.HWRING", "android.intent.category.HNRING") to "const-string v0, \"android.intent.category.DEFAULT\"",
    )
    val sites = mutableListOf<Triple<String, com.android.tools.smali.dexlib2.iface.Method, String>>()
    classDefForEach { c ->
        for (m in c.methods) {
            if (m.parameterTypes.isNotEmpty() || m.returnType != "Ljava/lang/String;") continue
            val found = strings(m).toSet()
            answers.firstOrNull { found.containsAll(it.first) }?.let { sites += Triple(c.type, m, it.second) }
        }
    }
    for ((type, method, answer) in sites) {
        mutableClassDefBy(type).methods.first { it.name == method.name && it.parameterTypes.isEmpty() && it.returnType == method.returnType }
            .addInstructions(0, "$answer\nreturn-object v0")
    }
    return sites.size
}

/**
 * Off MagicOS Clock dies at once on com.hihonor.android.os.SystemPropertiesEx (a MagicOS framework class), its layouts
 * then on MagicOS's theme attributes and its themes on MagicOS's own. The extension carries stand-ins for the 59 MagicOS
 * classes it uses (generated by .github/scripts/honor-standins; TextArrowPreference hand-written), including the
 * framework widgets its layouts inflate; on an Honor phone the framework's own classes load first, so they never run
 * there. What only Honor's system copy may do (write secure settings, pick ringtones and cities in MagicOS's own
 * apps) gets a plain Android answer; alarms ring through the full-screen notification Clock already posts.
 */
@Suppress("unused")
val enableNonMagicOsPatch = bytecodePatch(
    name = "Enable non-MagicOS ROM support",
    description = "Makes Honor Clock usable on non-Honor ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HONOR_CLOCK)
    dependsOn(magicResourcesPatch)
    extendWith(HONOR_CLOCK_EXTENSION)

    execute {
        val replaced = replaceMagicFrameworkIds("Lorg/ungoogled/honorclock/Magic;", CLOCK_CODE_ANSWERS)
        log.info("MagicOS framework ids in code: ${replaced.map { "0x%08x=%d".format(it.key, it.value) }}")
        if (replaced.size < 2) throw PatchException("only ${replaced.size} MagicOS framework ids found in code")

        val lookups = redirectMagicFrameworkLookups("Lorg/ungoogled/honorclock/Magic;")
        log.info("Resource lookups: $lookups")
        if (lookups < 6) throw PatchException("only $lookups resource lookups found")

        // Settings writes only a system app may make (WRITE_SECURE_SETTINGS) killed Clock at start: see SettingsWrites.
        val moved = redirectCalls(
            listOf(
                "Secure" to "putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z",
                "Secure" to "putLong(Landroid/content/ContentResolver;Ljava/lang/String;J)Z",
                "Secure" to "putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z",
                "System" to "putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z",
            ).associate { (table, shape) ->
                "Landroid/provider/Settings\$$table;->$shape" to
                    "$EXT/SettingsWrites;->${table.lowercase()}${shape.first().uppercase()}${shape.drop(1)}"
            },
        )
        log.info("Settings writes: $moved")
        // Measured against 12.0.1.196: 6 Secure writes, 1 System write.
        if (moved < 7) throw PatchException("only $moved Settings writes found")

        val sounds = timerSoundFallback()
        log.info("Timer sound fallbacks: $sounds")
        // Measured: the timer service's default and the timer page's (its sound setting).
        if (sounds != 2) throw PatchException("timer sound fallback: $sounds places")

        val pickers = cityPickerInApp()
        log.info("City pickers: $pickers")
        if (pickers != 1) throw PatchException("city picker: $pickers places")

        val ringtonePickers = androidRingtonePicker()
        log.info("Ringtone pickers: $ringtonePickers")
        if (ringtonePickers != 2) throw PatchException("ringtone picker package/category: $ringtonePickers methods")

        val lists = preferenceListsAsAndroid()
        log.info("Preference lists: $lists instructions")
        // Measured: the alarm editor's fragment (check-cast + 3 calls) and the Muslim alarm's (instance-of, check-cast, 2 calls).
        if (lists < 8) throw PatchException("only $lists preference list instructions found")
    }
}
