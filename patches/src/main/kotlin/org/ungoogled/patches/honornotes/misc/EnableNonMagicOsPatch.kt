package org.ungoogled.patches.honornotes.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.honor.MAGIC_DAY_NIGHT
import org.ungoogled.patches.honor.MAGIC_DAY_NIGHT_NO_ACTION_BAR
import org.ungoogled.patches.honor.MAGIC_DAY_NIGHT_TRANSLUCENT
import org.ungoogled.patches.honor.convertMagicInterpolators
import org.ungoogled.patches.honor.followMagicDayNight
import org.ungoogled.patches.honor.replaceMagicFrameworkLiterals
import org.ungoogled.patches.honor.replaceMagicFrameworkReferences
import org.ungoogled.patches.honor.useMagicWidgets
import org.ungoogled.patches.honornotes.HonorNotesConstants.COMPATIBILITY_HONOR_NOTES
import org.ungoogled.patches.samsung.redirectCalls

internal const val HONOR_NOTES_EXTENSION = "extensions/honornotes.mpe"

private val log = java.util.logging.Logger.getLogger("HonorNotes")

/**
 * MagicOS framework references in Notes' resources without a plain value: the app's own copies of what MagicOS's
 * theme gives (Honor's UI kit in the app ships them: colours with their night values, icsvg icons, sizes), else
 * Android's nearest answer.
 */
private val MAGIC_EXTRA = mapOf(
    "?0x020100bc" to "@color/magic_color_1", // attr/magicColor1
    "?0x020100bd" to "@color/magic_color_2", // attr/magicColor2
    "?0x020100be" to "@color/magic_color_3", // attr/magicColor3
    "?0x020100bf" to "@color/magic_color_4", // attr/magicColor4
    "?0x020100c1" to "@color/magic_color_6", // attr/magicColor6
    "?0x020100c3" to "@color/magic_color_8", // attr/magicColor8
    "?0x020100c4" to "@color/magic_color_9", // attr/magicColor9
    "?0x020100c6" to "@color/magic_color_11", // attr/magicColor11
    "?0x020100ef" to "@string/magic_text_font_family_regular", // attr/textFontFamilyRegular
    "?0x020100f0" to "@string/magic_text_font_family_medium", // attr/textFontFamilyMedium
    "?0x020100f8" to "@color/magic_color_subheader_divider", // attr/colorSubheaderDivider
    "?0x020100fd" to "@color/magic_selector_color_tertiary", // attr/colorTertiary
    "?0x02010103" to "@color/magic_functional_blue", // attr/textColorPrimaryActivated
    "?0x0201017b" to "@color/magic_listcard_bg", // attr/colorListCardBg
    "@0x02030383" to "@drawable/icsvg_hnrecorder_stop_filled", // drawable/icsvg_hnrecorder_stop_filled
    "@0x020305ba" to "@drawable/icsvg_hnnotepad_todos", // drawable/icsvg_hnnotepad_todos
    "@0x02030771" to "@drawable/icsvg_public_add", // drawable/icsvg_public_add
    "@0x02030772" to "@drawable/icsvg_public_add_filled", // drawable/icsvg_public_add_filled
    "@0x02030789" to "@drawable/icsvg_public_back", // drawable/icsvg_public_back
    "@0x0203079a" to "@drawable/icsvg_public_cancel", // drawable/icsvg_public_cancel
    "@0x020307d9" to "@drawable/icsvg_public_edit_fullscreen", // drawable/icsvg_public_edit_fullscreen
    "@0x020307fa" to "@drawable/icsvg_public_fullscreen", // drawable/icsvg_public_fullscreen
    "@0x02030815" to "@drawable/icsvg_public_list_cycle", // drawable/icsvg_public_list_cycle
    "@0x02030819" to "@drawable/icsvg_public_lock", // drawable/icsvg_public_lock
    "@0x0203083f" to "@drawable/icsvg_public_next12", // drawable/icsvg_public_next12
    "@0x02030842" to "@drawable/icsvg_public_note", // drawable/icsvg_public_note
    "@0x02030853" to "@drawable/icsvg_public_pause_filled", // drawable/icsvg_public_pause_filled
    "@0x02030867" to "@drawable/icsvg_public_play_filled", // drawable/icsvg_public_play_filled
    "@0x020308c9" to "@drawable/icsvg_public_todo", // drawable/icsvg_public_todo
    "@0x020308ca" to "@drawable/icsvg_public_todo_filled", // drawable/icsvg_public_todo_filled
    "@0x020308d3" to "@drawable/icsvg_public_unlock_filled", // drawable/icsvg_public_unlock_filled
    "@0x02030a83" to "@drawable/icsvg_public_tag_filled", // drawable/icsvg_public_tag_filled
    "@0x02031328" to "@drawable/icsvg_public_edit_fullscreen_bold", // drawable/icsvg_public_edit_fullscreen_bold
    "@0x02031343" to "@drawable/icsvg_public_fullscreen_bold", // drawable/icsvg_public_fullscreen_bold
    "@0x0203134b" to "@drawable/icsvg_public_handwrite_bold", // drawable/icsvg_public_handwrite_bold
    "@0x02031392" to "@drawable/icsvg_public_note_bold", // drawable/icsvg_public_note_bold
    "@0x020313ad" to "@drawable/icsvg_public_recorder_bold", // drawable/icsvg_public_recorder_bold
    "@0x020313d2" to "@drawable/icsvg_public_tag_bold", // drawable/icsvg_public_tag_bold
    "@0x02050177" to "@color/magic_primary", // color/magic_primary
    "@0x02050186" to "@color/magic_gray_2", // color/magic_gray_2
    "@0x0205019b" to "@color/magic_functional_red", // color/magic_functional_red
    "@0x020501a1" to "@color/magic_color_gray_1", // color/magic_color_gray_1
    "@0x020501aa" to "@color/magic_color_gray_10", // color/magic_color_gray_10
    "?0x02010016" to "@android:style/Widget.DeviceDefault.Button.Small", // attr/smallButtonStyleEmphasize
    "?0x02010074" to "?android:attr/colorControlHighlight", // attr/clickEffectColor
    "@0x020300cb" to "?android:attr/selectableItemBackground", // drawable/item_background_magic
    "@0x020302f8" to "@android:color/transparent", // drawable/search_bg_actived
    "@0x02060008" to "@style/$MAGIC_DAY_NIGHT", // style/Theme.Magic
    "@0x02060009" to "@style/$MAGIC_DAY_NIGHT_NO_ACTION_BAR", // style/Theme.Magic.NoActionBar
    "@0x0206001a" to "@style/$MAGIC_DAY_NIGHT_TRANSLUCENT", // style/Theme.Magic.Translucent
    "@0x0206002b" to "@style/$DIALOG_ALERT", // style/Theme.Magic.Dialog.Alert
    "@0x020601a0" to "@android:style/Widget.Material.SearchView", // style/Widget.Magic.SearchView.Status
    "@0x02080004" to "@anim/cubic_bezier_interpolator_type_33_33", // interpolator/cubic_bezier_interpolator_type_33_33
    "@0x020800ad" to "@anim/cubic_bezier_interpolator_type_20_80", // interpolator/cubic_bezier_interpolator_type_20_80
)

/** MagicOS's day/night alert dialog theme: Android's light one, its dark one at night (added to values / values-night). */
private const val DIALOG_ALERT = "MagicDialogAlert"

private val ICON_SRC = Regex("""r0x0201011e="[@?]*0x(02[0-9a-f]{6})"""")

/**
 * Notes' resources name MagicOS's theme and framework resources (see replaceMagicFrameworkReferences), its screens
 * follow dark mode through MagicOS's theme (see followMagicDayNight), and 33 icons are MagicOS's own vector icon
 * drawable (<drawable class="com.hihonor.android.widget.HnIconVectorDrawable" androidhnext:iconSrc=...>), which only
 * MagicOS can inflate and whose attributes Morphe cannot even re-encode: each becomes an <inset> of the app's own copy
 * of its icon.
 */
private val magicResourcesPatch = resourcePatch(
    description = "Gives Honor Notes the sizes, icons and day/night theme MagicOS would.",
) {
    execute {
        var icons = 0
        for (path in listDecodableFiles("res/**").filter { it.endsWith(".xml") }) {
            val file = get(path)
            if (!file.isFile) continue
            val text = file.readText()
            if (!text.contains("com.hihonor.android.widget.HnIconVectorDrawable")) continue
            val source = ICON_SRC.find(text)?.groupValues?.get(1)
                ?: throw PatchException("MagicOS icon without a source: $path")
            val icon = MAGIC_EXTRA["@0x$source"] ?: throw PatchException("MagicOS icon @0x$source has no copy in the app ($path)")
            file.writeText(
                """<?xml version="1.0" encoding="utf-8"?>
                |<inset xmlns:android="http://schemas.android.com/apk/res/android" android:drawable="$icon" />
                |""".trimMargin(),
            )
            icons++
        }
        log.info("MagicOS icons: $icons")
        // Measured against 18.0.4.817: 33.
        if (icons < 30) throw PatchException("only $icons MagicOS icons found")

        for ((path, parent) in listOf(
            "res/values/styles.xml" to "@android:style/Theme.DeviceDefault.Light.Dialog.Alert",
            "res/values-night/styles.xml" to "@android:style/Theme.DeviceDefault.Dialog.Alert",
        )) {
            document(path).use { xml ->
                xml.documentElement.appendChild(xml.createElement("style").apply {
                    setAttribute("name", DIALOG_ALERT)
                    setAttribute("parent", parent)
                })
            }
        }

        // MagicOS's expanded app bar layout, which the main list inflates (runtime MagicAppbar): a toolbar and a holder
        // for the app's title view, with the ids the app looks up as androidhnext's (Magic.getIdentifier: "magic_").
        document("res/values/ids.xml").use { xml ->
            for (name in listOf("magic_appbar_layout", "magic_toolbar", "magic_collapsing_toolbar")) {
                xml.documentElement.appendChild(xml.createElement("item").apply {
                    setAttribute("type", "id")
                    setAttribute("name", name)
                })
            }
        }
        get("res/layout/magic_expanded_appbar.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
            |<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:layout_width="match_parent" android:layout_height="match_parent">
            |    <LinearLayout android:id="@id/magic_appbar_layout" android:orientation="vertical" android:clickable="true"
            |        android:layout_width="match_parent" android:layout_height="wrap_content">
            |        <com.hihonor.android.widget.HwToolbar android:id="@id/magic_toolbar"
            |            android:layout_width="match_parent" android:layout_height="?android:attr/actionBarSize" />
            |        <FrameLayout android:id="@id/magic_collapsing_toolbar" android:paddingHorizontal="16dp"
            |            android:layout_width="match_parent" android:layout_height="wrap_content" />
            |    </LinearLayout>
            |</FrameLayout>
            |""".trimMargin(),
        )

        val replaced = replaceMagicFrameworkReferences(MAGIC_EXTRA)
        log.info("MagicOS framework references: $replaced")
        if (replaced.size < 60) throw PatchException("only ${replaced.size} MagicOS framework references found")

        val (activities, styles) = followMagicDayNight()
        log.info("Day/night themes: $activities activities, $styles styles")

        val interpolators = convertMagicInterpolators()
        log.info("MagicOS interpolators: $interpolators")

        // The app casts views of plain tags to MagicOS's widget classes, which MagicOS's LayoutInflater makes.
        val layouts = useMagicWidgets(
            mapOf(
                "ImageView" to "com.hihonor.android.widget.ImageView",
                "TextView" to "com.hihonor.android.widget.HwTextView",
                "EditText" to "com.hihonor.android.widget.EditText",
                "ScrollView" to "com.hihonor.android.widget.ScrollView",
                "ListView" to "com.hihonor.android.widget.ListView",
                "SearchView" to "com.hihonor.android.widget.SearchView",
                "Spinner" to "com.hihonor.android.widget.Spinner",
                "Toolbar" to "com.hihonor.android.widget.HwToolbar",
            ),
        )
        log.info("Layouts with MagicOS widgets: $layouts")
    }
}

/** Notes' code names MagicOS framework resources by id: each gets the app's own copy or Android's nearest one. */
private val magicLiteralsPatch = bytecodePatch(
    description = "Answers MagicOS resource ids in Honor Notes' code.",
) {
    dependsOn(magicResourcesPatch)
    extendWith(HONOR_NOTES_EXTENSION)

    execute {
        fun id(type: ResourceType, name: String) = resourceId(type, name).toInt()
        val answers = mapOf(
            0x020100bc to id(ResourceType.COLOR, "magic_color_1"), // attr/magicColor1
            0x020100bd to id(ResourceType.COLOR, "magic_color_2"), // attr/magicColor2
            0x020100bf to id(ResourceType.COLOR, "magic_color_4"), // attr/magicColor4
            0x020100c1 to id(ResourceType.COLOR, "magic_color_6"), // attr/magicColor6
            0x020100c3 to id(ResourceType.COLOR, "magic_color_8"), // attr/magicColor8
            0x020100c6 to id(ResourceType.COLOR, "magic_color_11"), // attr/magicColor11
            0x020100c7 to id(ResourceType.DIMEN, "magic_dimens_default_start"), // attr/defaultPaddingStart
            0x020100c8 to id(ResourceType.DIMEN, "magic_dimens_max_start"), // attr/maxPaddingStart
            0x020100c9 to id(ResourceType.DIMEN, "magic_dimens_default_end"), // attr/defaultPaddingEnd
            0x020100ca to id(ResourceType.DIMEN, "magic_dimens_max_end"), // attr/maxPaddingEnd
            0x020100cb to id(ResourceType.DIMEN, "magic_dimens_default_top"), // attr/defaultPaddingTop
            0x020100cd to id(ResourceType.DIMEN, "magic_dimens_default_bottom_fixed"), // attr/defaultPaddingBottomFixed
            0x020100e2 to id(ResourceType.DIMEN, "magic_text_size_headline7"), // attr/textSizeHeadline7
            0x020100e3 to id(ResourceType.DIMEN, "magic_text_size_subtitle1"), // attr/textSizeSubTitle1
            0x020100e4 to id(ResourceType.DIMEN, "magic_text_size_subtitle2"), // attr/textSizeSubTitle2
            0x020100e8 to id(ResourceType.DIMEN, "magic_text_size_body1"), // attr/textSizeBody1
            0x020100e9 to id(ResourceType.DIMEN, "magic_text_size_body2"), // attr/textSizeBody2
            0x020100ea to id(ResourceType.DIMEN, "magic_text_size_body3"), // attr/textSizeBody3
            0x020100ef to id(ResourceType.STRING, "magic_text_font_family_regular"), // attr/textFontFamilyRegular
            0x020100f0 to id(ResourceType.STRING, "magic_text_font_family_medium"), // attr/textFontFamilyMedium
            0x02010103 to id(ResourceType.COLOR, "magic_functional_blue"), // attr/textColorPrimaryActivated
            0x0201015a to id(ResourceType.DIMEN, "magic_dimens_element_vertical_middle_2"), // attr/elementsMarginVerticalM2
            0x02010340 to id(ResourceType.COLOR, "magic_color_deco_bg_3_start"), // attr/magicColorDecoBg3Start
            0x02010341 to id(ResourceType.COLOR, "magic_color_deco_bg_3_end"), // attr/magicColorDecoBg3End
            0x02050184 to id(ResourceType.COLOR, "magic_black"), // color/magic_black
            0x0205019b to id(ResourceType.COLOR, "magic_functional_red"), // color/magic_functional_red
            0x020501aa to id(ResourceType.COLOR, "magic_color_gray_10"), // color/magic_color_gray_10
            0x020800aa to id(ResourceType.INTERPOLATOR, "fast_out_slow_in"), // interpolator/fast_out_slow_in
            0x020a000b to id(ResourceType.ANIM, "activity_close_enter"), // anim/activity_close_enter
            0x020a000d to id(ResourceType.ANIM, "activity_close_exit"), // anim/activity_close_exit
            0x020e012a to id(ResourceType.DIMEN, "magic_master_subtitle"), // dimen/magic_master_subtitle
            0x02030233 to id(ResourceType.DRAWABLE, "folder_spinner_popwindow_bg_corners"), // drawable/spinner_menu
            0x02060008 to 0x010302e3, // style/Theme.Magic -> android:style/Theme.DeviceDefault.DayNight
            0x0206002b to 0, // style/Theme.Magic.Dialog.Alert -> the activity's own alert dialog theme
            0x020600cd to 0x01030128, // style/Theme.Magic.Dark -> android:style/Theme.DeviceDefault
            0x020601ae to 0, // style/Theme.Magic.Dialog.Alert.Positive
            0x02080004 to id(ResourceType.ANIM, "cubic_bezier_interpolator_type_33_33"),
            0x020800a3 to id(ResourceType.ANIM, "hwrecyclerview_cubic_bezier_interpolator_type_0_100"),
            0x020800ac to id(ResourceType.ANIM, "cubic_bezier_interpolator_type_40_60"),
            0x020800ad to id(ResourceType.ANIM, "cubic_bezier_interpolator_type_20_80"),
            0x020a0000 to 0x010a0000, // anim/fragment_open_right_in -> android:anim/fade_in
            0x020a0001 to 0x010a0001, // anim/fragment_open_left_out -> android:anim/fade_out
            0x020a0002 to 0x010a0000, // anim/fragment_close_left_in
            0x020a0003 to 0x010a0001, // anim/fragment_close_right_out
            0x020a0008 to 0x010a0000, // anim/activity_open_enter
            0x020a0010 to 0x010a0001, // anim/activity_open_exit
        )
        val (replaced, moved) = replaceMagicFrameworkLiterals(answers, "Lorg/ungoogled/honornotes/Magic;")
        log.info("MagicOS ids in code: $replaced, resolveAttribute calls: $moved")
        if (replaced.size < 40) throw PatchException("only ${replaced.size} MagicOS ids found in code")
    }
}

/** Notes' ContentResolver calls that reach a provider or watch one. */
private val RESOLVER_CALLS = mapOf(
    "Landroid/content/ContentResolver;" to setOf(
        "registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;)V",
        "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
        "query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
        "query(Landroid/net/Uri;[Ljava/lang/String;Landroid/os/Bundle;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
        "insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
        "update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I",
        "delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I",
        "bulkInsert(Landroid/net/Uri;[Landroid/content/ContentValues;)I",
        "call(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
        "call(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
        "applyBatch(Ljava/lang/String;Ljava/util/ArrayList;)[Landroid/content/ContentProviderResult;",
        "notifyChange(Landroid/net/Uri;Landroid/database/ContentObserver;)V",
        "notifyChange(Landroid/net/Uri;Landroid/database/ContentObserver;Z)V",
        "notifyChange(Landroid/net/Uri;Landroid/database/ContentObserver;I)V",
    ),
)

private const val HW_TOOLBAR = "Lcom/hihonor/android/widget/HwToolbar;"
private const val ACTION_BAR_EX = "Lcom/hihonor/android/widget/ActionBarEx;"

/**
 * Notes compiles in its own copy of MagicOS's HwToolbar (an Android Toolbar), so the stand-ins cannot name it: its
 * ActionBarEx calls that take one (the edit screens' cancel/done icons and their states) go to MagicToolbar's
 * Toolbar versions instead.
 */
private val toolbarPatch = bytecodePatch(
    description = "Draws MagicOS's toolbar icons in Honor Notes.",
) {
    extendWith(HONOR_NOTES_EXTENSION)

    execute {
        fun Any.match(): MethodReference? {
            val r = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
            return if (r.definingClass == ACTION_BAR_EX && HW_TOOLBAR in r.parameterTypes.map { it.toString() }) r else null
        }
        val sites = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) if (m.implementation?.instructions?.any { it.match() != null } == true) sites += c.type to m
        }
        var moved = 0
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                val r = insn.match() ?: continue
                val params = r.parameterTypes.joinToString("") { p -> if (p.toString() == HW_TOOLBAR) "Landroid/widget/Toolbar;" else p.toString() }
                val to = "Lorg/ungoogled/honornotes/MagicToolbar;->${r.name}($params)${r.returnType}"
                val call = when (insn) {
                    is FiveRegisterInstruction -> listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                        .take(insn.registerCount).joinToString(", ", "invoke-static { ", " }, $to") { "v$it" }
                    is RegisterRangeInstruction ->
                        "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + insn.registerCount - 1} }, $to"
                    else -> throw PatchException("unexpected ${insn.opcode} in $type.${method.name}")
                }
                mutable.replaceInstruction(index, call)
                moved++
            }
        }
        log.info("Toolbar calls: $moved")
        if (moved < 5) throw PatchException("only $moved toolbar calls found")
    }
}

/** The app's helper that reads a theme colour by attribute (obtainStyledAttributes + getColor(0, 0)). */
private object ThemeColorFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;", "I"),
    custom = { method, _ ->
        val calls = method.implementation?.instructions?.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> r.definingClass + r.name }
        } ?: emptyList()
        "Landroid/content/res/Resources\$Theme;obtainStyledAttributes" in calls && "Landroid/content/res/TypedArray;getColor" in calls &&
            calls.size < 6
    },
)

/**
 * Notes' window and resource plumbing: it targets Android 16, so on Android 15 its windows are edge to edge and its
 * setDecorFitsSystemWindows(true) is ignored; it looks MagicOS's themes up by name; it reads MagicOS colour attributes
 * the patch answers with plain colours (Magic: setDecorFitsSystemWindows, getIdentifier, themeColor); and it calls
 * MagicOS's and Honor's other apps' providers, absent here (Resolvers).
 */
private val runtimePatch = bytecodePatch(
    description = "Fits Honor Notes' windows, theme lookups and providers to Android.",
) {
    dependsOn(magicLiteralsPatch)
    extendWith(HONOR_NOTES_EXTENSION)

    execute {
        val magic = "Lorg/ungoogled/honornotes/Magic;"
        val fits = redirectCalls(magic, mapOf("Landroid/view/Window;" to setOf("setDecorFitsSystemWindows(Z)V")))
        val lookups = redirectCalls(
            magic,
            mapOf("Landroid/content/res/Resources;" to setOf("getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I")),
        )
        val providers = redirectCalls("Lorg/ungoogled/honornotes/Resolvers;", RESOLVER_CALLS)
        ThemeColorFingerprint.method.apply {
            if (implementation!!.registerCount - 2 < 1) throw PatchException("theme colour helper has no local register")
            addInstructions(
                0,
                """
                    invoke-static { p0, p1 }, $magic->themeColor(Landroid/content/Context;I)I
                    move-result v0
                    return v0
                """,
            )
        }
        log.info("setDecorFitsSystemWindows: $fits, getIdentifier: $lookups, ContentResolver: $providers")
    }
}

private const val NOTE_LIST = "Lcom/hihonor/notes/list/NotePadNewFragment;"

/**
 * Selection mode's close button (the toolbar's start icon) sits under the app's own drawer button off MagicOS: the
 * drawer button is hidden while the list selects (Selection).
 */
private val selectionPatch = bytecodePatch(
    description = "Keeps Honor Notes' selection close button tappable.",
) {
    extendWith(HONOR_NOTES_EXTENSION)

    execute {
        val list = mutableClassDefBy(NOTE_LIST)
        for ((name, call) in listOf("refreshBatchDeleteToolbar" to "on", "refreshMoreToolbar" to "off")) {
            val method = list.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }
                ?: throw PatchException("$NOTE_LIST.$name() not found")
            method.addInstructions(0, "invoke-static/range { p0 .. p0 }, Lorg/ungoogled/honornotes/Selection;->$call(Ljava/lang/Object;)V")
        }
    }
}

private const val VOICE_KIT = "Lcom/hihonor/intelligent/voicekit/VoiceKit;"
private const val SDK_INIT_CALLBACK = "Lcom/hihonor/intelligent/callback/SdkInitCallBack;"

/**
 * Honor's AI (VoiceKit: translation, writing assistant) starts with every note the editor opens: a route lookup at
 * Honor's cloud (grs.hihonorcloud.com: app name, country) on the main thread, then a device-attested sign-in that
 * fails off Honor phones anyway. Its init now answers "not available" at once (the app's own failure path), so the
 * editor sends nothing and does not wait for it.
 */
private val aiPatch = bytecodePatch(
    description = "Keeps Honor Notes' cloud AI off.",
) {
    execute {
        val init = mutableClassDefBy(VOICE_KIT).methods.firstOrNull {
            it.name == "init" && it.returnType == "V" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;", "Landroid/os/Bundle;", SDK_INIT_CALLBACK)
        } ?: throw PatchException("VoiceKit.init not found")
        if (init.implementation!!.registerCount - 4 < 2) throw PatchException("VoiceKit.init has too few registers")
        init.addInstructions(
            0,
            """
                if-eqz p3, :done
                const v0, 400004
                const-string v1, "Honor AI is not available off MagicOS"
                invoke-interface { p3, v0, v1 }, $SDK_INIT_CALLBACK->result(ILjava/lang/String;)V
                :done
                return-void
            """,
        )
    }
}

/**
 * Handwritten notes need MagicOS's pen engine (com.hihonor.penkit, a system library): creating one died on its
 * absent engine (NoSuchMethodError in NoteBuilder.build), as did opening one. The new-note bubble's handwriting button
 * is hidden, the bubbles' way into a new handwritten note shows a toast instead, and the editor closes with that toast
 * rather than add its handwritten-note page (Handwriting).
 */
private val handwritingPatch = bytecodePatch(
    description = "Keeps Honor Notes' handwriting off without its pen engine.",
) {
    extendWith(HONOR_NOTES_EXTENSION)

    execute {
        val bubble = mutableClassDefBy("Lcom/hihonor/notes/note/view/BaseNoteBubble;")
        val start = bubble.methods.firstOrNull { it.name == "startHandWritingDetailPage" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("BaseNoteBubble.startHandWritingDetailPage() not found")
        start.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p0 }, Lorg/ungoogled/honornotes/Handwriting;->unavailable(Landroid/view/View;)V
                return-void
            """,
        )
        val create = mutableClassDefBy("Lcom/hihonor/notes/note/view/CreateNoteBubble;")
        val init = create.methods.firstOrNull { it.name == "initView" && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;") }
            ?: throw PatchException("CreateNoteBubble.initView not found")
        // R8 reuses p0 later in initView: the hide is posted from its start, while p0 is still the bubble.
        init.addInstructions(0, "invoke-static/range { p0 .. p0 }, Lorg/ungoogled/honornotes/Handwriting;->hideLater(Landroid/view/View;)V")
        val editor = mutableClassDefBy("Lcom/hihonor/notes/note/NoteEditorActivity;")
        val add = editor.methods.firstOrNull {
            it.name == "addFragment" && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/app/Fragment;", "Ljava/lang/String;")
        } ?: throw PatchException("NoteEditorActivity.addFragment not found")
        if (add.implementation!!.registerCount - 3 < 1) throw PatchException("NoteEditorActivity.addFragment has no local register")
        add.addInstructions(
            0,
            """
                invoke-static { p0, p2 }, Lorg/ungoogled/honornotes/Handwriting;->blocked(Landroid/app/Activity;Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :open
                return-void
                :open
                nop
            """,
        )
    }
}

/** Settings' "Notifications": MagicOS's notification centre intent, started through the app's safe-start helper. */
private object NotificationSettingsFingerprint : Fingerprint(
    strings = listOf("hihonor.intent.action.NOTIFICATIONSETTING"),
)

/** Settings' "Notifications" opens Android's notification settings for Notes when MagicOS's is absent (Intents). */
private val intentsPatch = bytecodePatch(
    description = "Opens Android's notification settings for Honor Notes.",
) {
    extendWith(HONOR_NOTES_EXTENSION)

    execute {
        NotificationSettingsFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val index = instructions.indexOfFirst {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_STATIC && ref != null &&
                    ref.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;", "Landroid/content/Intent;")
            }
            if (index < 0) throw PatchException("notification settings start not found")
            val call = instructions[index] as FiveRegisterInstruction
            addInstructions(
                index,
                "invoke-static { v${call.registerC}, v${call.registerD} }, " +
                    "Lorg/ungoogled/honornotes/Intents;->fit(Landroid/content/Context;Landroid/content/Intent;)V",
            )
        }
    }
}

/**
 * Off MagicOS Notes dies at once on com.hihonor.android.os.SystemPropertiesEx (HnNotePadApplication's <clinit>). The
 * extension carries stand-ins for the MagicOS classes it uses (generated by .github/scripts/honor-standins: Honor's AI,
 * pen, co-auth and distributed services answer "absent"); on an Honor phone the framework's own classes load first.
 */
@Suppress("unused")
val enableNonMagicOsPatch = bytecodePatch(
    name = "Enable non-MagicOS ROM support",
    description = "Makes Honor Notes usable on non-Honor ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HONOR_NOTES)
    dependsOn(magicResourcesPatch, magicLiteralsPatch, toolbarPatch, runtimePatch, selectionPatch, intentsPatch, aiPatch, handwritingPatch)
    extendWith(HONOR_NOTES_EXTENSION)
}
