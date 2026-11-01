package org.ungoogled.patches.lg

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

/**
 * A value of LG's framework resources (the "com.lge" package, id 0x02, which only LG's ROM has): [name] as LG names it,
 * [day] its value, [night] its value in dark mode where LG's differs. Colours with a night value become app colours
 * named "lg_<name>" (values and values-night); everything else is written in place.
 */
internal class LgValue(val name: String, val day: String, val night: String? = null)

/**
 * What LG's framework answers for the theme attributes of its default theme (Theme.LGE.Default, "?0x0201...") and the
 * framework resources ("@0x02...") LG's apps reference, read from lge-res 3.0.0 (LG V50 ThinQ, Android 12; signed
 * with LG's platform key 4274243d...) by .github/scripts/lg-standins/lgres.py. LG's public ids hold for every LG UX
 * version.
 */
internal val LG_FRAMEWORK_VALUES = mapOf(
    "?0x0201000a" to LgValue("colorControlNormalType2", "#33a2a2a2", "#33737373"),
    "?0x0201000b" to LgValue("textColorPrimaryType1", "#cc262626", "#ccfafafa"),
    "?0x0201000c" to LgValue("textColorPrimaryType2", "#b2262626", "#b2fafafa"),
    "?0x0201000d" to LgValue("textColorPrimaryType3", "#99262626", "#99fafafa"),
    "?0x0201000e" to LgValue("textColorPrimaryType4", "#66262626", "#66fafafa"),
    "?0x0201000f" to LgValue("textColorPrimaryType5", "#4c262626", "#4cfafafa"),
    "?0x02010010" to LgValue("textColorHintType1", "#33b2b2b2", "#33262626"),
    "?0x02010011" to LgValue("colorBackgroundType1", "#ccfafafa", "#cc202020"),
    // colorActionBarIcon: LG's accent (color_action_bar_icon_material).
    "?0x02010025" to LgValue("colorActionBarIcon", "#ff00b13a", "#ff00bc16"),
    "?0x02010032" to LgValue("expandActionBarSize", "142.0dp"),
    "@0x020800c5" to LgValue("primary_text_default_material_light", "#ff262626", "#fffafafa"),
    "@0x020800cc" to LgValue("list_separator_background_color", "#fff0f0f0", "#ff080808"),
    "@0x020800cd" to LgValue("list_separator_text_color", "#ff7f7f7f", "#ff8c8c8c"),
    "@0x02080105" to LgValue("action_bar_title_text_color", "#ff262626", "#fffafafa"),
    "@0x0208014a" to LgValue("component_background_color", "#ffe9e9e9", "#ff2c2c2c"),
    "@0x0208019f" to LgValue("secondary_text_default_material_light", "#ff8f8f8f", "#ff878787"),
    "@0x020c003d" to LgValue("preference_item_padding_top", "11.0dp"),
    "@0x020c003e" to LgValue("preference_item_padding_bottom", "11.0dp"),
    "@0x020c00c5" to LgValue("content_foreground_padding_end", "8.0dp"),
    "@0x020c00c6" to LgValue("content_foreground_padding_start", "8.0dp"),
    "@0x020c011d" to LgValue("type_a01_sp", "10.0sp"),
    "@0x020c011e" to LgValue("type_a01_dp", "10.0dp"),
    "@0x020c011f" to LgValue("type_a02_sp", "12.0sp"),
    "@0x020c0120" to LgValue("type_a02_dp", "12.0dp"),
    "@0x020c0121" to LgValue("type_b01_sp", "12.0sp"),
    "@0x020c0122" to LgValue("type_b01_dp", "12.0dp"),
    "@0x020c0123" to LgValue("type_b02_sp", "12.0sp"),
    "@0x020c0124" to LgValue("type_b02_dp", "12.0dp"),
    "@0x020c0125" to LgValue("type_c01_sp", "15.0sp"),
    "@0x020c0126" to LgValue("type_c01_dp", "15.0dp"),
    "@0x020c0127" to LgValue("type_c02_sp", "15.0sp"),
    "@0x020c0129" to LgValue("type_d01_sp", "16.0sp"),
    "@0x020c012a" to LgValue("type_d01_dp", "16.0dp"),
    "@0x020c012b" to LgValue("type_d02_sp", "16.0sp"),
    "@0x020c012d" to LgValue("type_d03_sp", "16.0sp"),
    "@0x020c012e" to LgValue("type_d03_dp", "16.0dp"),
    "@0x020c012f" to LgValue("type_e01_sp", "18.0sp"),
    "@0x020c0130" to LgValue("type_e01_dp", "18.0dp"),
    "@0x020c0133" to LgValue("type_f01_sp", "17.0sp"),
    "@0x020c0134" to LgValue("type_f01_dp", "17.0dp"),
    "@0x020c0135" to LgValue("type_f02_sp", "20.0sp"),
    "@0x020c0171" to LgValue("type_ae03_dp", "11.0dp"),
    "@0x020c017d" to LgValue("type_ag03_dp", "13.0dp"),
    "@0x020c0180" to LgValue("type_ah01_sp", "14.0sp"),
    "@0x020c0195" to LgValue("type_al01_dp", "18.0dp"),
    "@0x020c0197" to LgValue("type_am01_dp", "19.0dp"),
    "@0x020c01a2" to LgValue("type_ap01_sp", "22.0sp"),
    "@0x020c01b2" to LgValue("type_at01_sp", "26.0sp"),
    "@0x020c01b4" to LgValue("type_at02_sp", "26.0sp"),
    "@0x020c01b5" to LgValue("type_at02_dp", "26.0dp"),
    "@0x020c01c0" to LgValue("type_ay01_sp", "32.0sp"),
    "@0x020c01c7" to LgValue("type_az01_dp", "33.0dp"),
    "@0x020d016f" to LgValue("app_bar_scrolling_view_behavior", "com.lge.lgdynamicactionbar.AppBarLayout\$ScrollingViewBehavior"),
)

/** The app style standing in for LG's default theme (see [replaceLgFrameworkReferences]). */
internal const val LG_DAY_NIGHT = "LgDayNight"

/**
 * LG's themes, widget styles and framework drawables and layouts, as Android's own or as resources the port adds (LG's
 * are Material underneath): LG's themes become [LG_DAY_NIGHT] and its variants, which follow dark mode as LG UX does;
 * "" drops the attribute that names it (Android's default applies, e.g. a preference's own layout).
 */
internal val LG_FRAMEWORK_ANSWERS = mapOf(
    "@0x020a01ac" to "@style/$LG_DAY_NIGHT", // style/Theme.LGE.Default
    "@0x020a01b6" to "@style/$LG_DAY_NIGHT.NoActionBar", // style/Theme.LGE.Default.NoActionBar
    "@0x020a01b9" to "@style/$LG_DAY_NIGHT.NoActionBar.Fullscreen", // style/Theme.LGE.Default.NoActionBar.Fullscreen
    "@0x020a01bc" to "@style/$LG_DAY_NIGHT.Wallpaper", // style/Theme.LGE.Default.Wallpaper
    "@0x020a01db" to "@style/$LG_DAY_NIGHT.Dialog.Alert", // style/Theme.LGE.Default.Dialog.Alert.Separated
    "@0x020a01f4" to "@android:style/Widget.Material.CompoundButton.RadioButton", // Widget.Material.CompoundButton.RadioButton.Blocked
    "@0x020a02c2" to "@android:style/Widget.Material.Button", // style/Widget.Material.Button.Large
    "?0x02010012" to "@android:style/Widget.Material.TextView", // attr/descriptionTextViewStyle
    "?0x02010014" to "@android:style/Widget.Material.CompoundButton.CheckBox", // attr/blockedCheckBoxStyle
    "?0x02010015" to "@android:style/Widget.Material.Button", // attr/buttonLargeStyle
    // LG UX's rounded-corner masks over list items and content cards: none (square corners).
    "@0x020202d4" to "@android:color/transparent", // drawable/content_foreground
    "@0x020202d5" to "@android:color/transparent", // drawable/content_foreground_bottom
    "@0x020202d6" to "@android:color/transparent", // drawable/content_foreground_top
    "@0x02020646" to "@android:color/transparent", // drawable/list_item_foreground
    "@0x02020647" to "@android:color/transparent", // drawable/list_item_foreground_bottom
    "@0x02020648" to "@android:color/transparent", // drawable/list_item_foreground_middle
    "@0x02020649" to "@android:color/transparent", // drawable/list_item_foreground_top
    "@0x02020066" to "?android:attr/listChoiceIndicatorMultiple", // drawable/btn_blocked_check_selector
    "@0x0202049b" to "@android:drawable/ic_menu_add", // drawable/ic_menu_add_drawable
    "@0x0202091a" to "@drawable/lg_floating_action_button_background", // drawable/floating_action_button_background
    "@0x0202091b" to "@drawable/lg_floating_action_button_add", // drawable/floating_action_button_add
    // LG's preference row layouts (rounded top / middle / bottom / single rows): Android's own preference layout.
    "@0x02030077" to "", // layout/preference_bottom
    "@0x0203008f" to "", // layout/preference_single
    "@0x02030090" to "", // layout/preference_top
    "@0x02030093" to "", // layout/preference_middle
)

/**
 * The same, for LG framework ids in code (see replaceLgFrameworkIds): LG's dialog building blocks (dialog_c_*) become
 * layouts the port adds ([addLgFrameworkLayouts]), its preference layouts Android's own, its themes the app style
 * [LG_DAY_NIGHT] stands behind or Android's default dialog theme (0), its row masks Android's transparent colour.
 */
internal val LG_FRAMEWORK_CODE_IDS = mapOf(
    0x02020646 to "0x0106000d", // drawable/list_item_foreground
    0x02020647 to "0x0106000d", // drawable/list_item_foreground_bottom
    0x02020648 to "0x0106000d", // drawable/list_item_foreground_middle
    0x02020649 to "0x0106000d", // drawable/list_item_foreground_top
    0x02030005 to "new:layout/lg_dialog_c_1", // layout/dialog_c_1: a message
    0x0203000c to "new:layout/lg_dialog_c_11", // layout/dialog_c_11: a check box
    0x02030010 to "new:layout/lg_dialog_c_21", // layout/dialog_c_21: a hint and a line
    0x02030015 to "new:layout/lg_dialog_c_frame2", // layout/dialog_c_frame2: the scrolling frame they go in
    0x02030077 to "new:android:layout/preference_material", // layout/preference_bottom
    0x0203008f to "new:android:layout/preference_material", // layout/preference_single
    0x02030090 to "new:android:layout/preference_material", // layout/preference_top
    0x02030093 to "new:android:layout/preference_material", // layout/preference_middle
    0x020a01b6 to "new:style/$LG_DAY_NIGHT.NoActionBar", // style/Theme.LGE.Default.NoActionBar
    0x020a01b9 to "new:style/$LG_DAY_NIGHT.NoActionBar.Fullscreen", // style/Theme.LGE.Default.NoActionBar.Fullscreen
    0x020a01db to "0x0", // style/Theme.LGE.Default.Dialog.Alert.Separated: the context's own alert dialog theme
    0x020c0129 to "new:dimen/lg_type_d01_sp", // dimen/type_d01_sp
    0x020c02a1 to "new:dimen/lg_expand_action_bar_main_title_padding_bottom", // dimen/expand_action_bar_main_title_padding_bottom
)

/** LG's default theme colours ([LG_DAY_NIGHT]): accent, bars, background and controls, by day and by night. */
private val LG_THEME_COLORS = listOf(
    LgValue("color_accent_ui", "#ff00b13a", "#ff00bc16"),
    LgValue("foreground_material_light", "#fff0f0f0", "#ff080808"),
    LgValue("background_material_light", "#fffafafa", "#ff202020"),
    LgValue("color_control_normal_ui", "#ffa2a2a2", "#ff737373"),
    LgValue("primary_text_default_material_light", "#ff262626", "#fffafafa"),
    LgValue("secondary_text_default_material_light", "#ff8f8f8f", "#ff878787"),
)

// "??0x..." / "@@0x..." is how a "dynamic" reference (aapt2's encoding for a shared library's ids) decodes.
private val LG_REFERENCE = Regex("""([?@])\1?0x(02[0-9a-f]{6})""", RegexOption.IGNORE_CASE)

/** An attribute whose whole value is a reference to LG's framework. */
private val LG_REFERENCE_ATTRIBUTE = Regex("""\s+[\w:]+="([?@])\1?0x(02[0-9a-f]{6})"""", RegexOption.IGNORE_CASE)

/** An attribute of LG's framework (named in its namespace, e.g. lge:layout_behavior), as Morphe decodes it. */
private val LG_ATTRIBUTE = Regex("""\s+(\w+):r0x(02[0-9a-f]{6})="([^"]*)"""", RegexOption.IGNORE_CASE)

/** A style item that sets one of LG's framework attributes. */
private val LG_STYLE_ITEM = Regex("""\s*<item name="(\w+:)?r0x02[0-9a-f]{6}"[^>]*>[^<]*</item>""", RegexOption.IGNORE_CASE)
private val LG_STYLE_ITEM_ID = Regex("""<item name="(?:\w+:)?r0x(02[0-9a-f]{6})"""", RegexOption.IGNORE_CASE)

private val LG_NAMESPACE = Regex("""\s+xmlns:(\w+)="http://schemas\.android\.com/apk/res/com\.lge"""")

private fun Element.children(tag: String) =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

/**
 * Off LG's ROM there is no "com.lge" resource package: a layout or style value that names one of its theme attributes
 * fails to inflate ("Failed to resolve attribute"), one that names one of its resources has nothing behind it, an app
 * style whose parent is LG's theme has no parent, and Morphe cannot re-encode a file that names the package at all.
 * Rewrites every such XML resource of the app (layouts, drawables, colours, ... and the values of its resource table):
 * - references become what LG's framework answers ([LG_FRAMEWORK_VALUES], [LG_FRAMEWORK_ANSWERS], then the port's
 *   [extra]: "?0x0201..." / "@0x02..." to a value or one of the app's own or added resources; "" drops the
 *   attribute);
 * - LG's framework attributes (lge:...) are dropped: only LG's own widgets read them; [attributes] (LG attribute id
 *   "0x0201...." to a function of its value) can turn one into something the port's stand-ins read instead;
 * - LG's themes become [LG_DAY_NIGHT] (Android's day/night theme with LG's colours), added with the colours used;
 *   an LG theme attribute the app's styles set per screen and its XML reads becomes an app attribute "lg_<name>"
 *   (LG's value in [LG_DAY_NIGHT]), so each screen keeps its own value.
 * A reference nothing answers stops the patch, listed. Returns the references replaced, per framework id.
 */
internal fun ResourcePatchContext.replaceLgFrameworkReferences(
    extra: Map<String, String> = emptyMap(),
    attributes: Map<String, (String) -> String> = emptyMap(),
): Map<String, Int> {
    val replaced = sortedMapOf<String, Int>()
    val unknown = sortedSetOf<String>()
    val colors = sortedMapOf<String, LgValue>()
    val files = listDecodableFiles("res/**").filter { it.endsWith(".xml") }

    // LG theme attributes the app's own styles set (per screen) and its XML reads become app attributes "lg_<name>"
    // (LG's default in [LG_DAY_NIGHT]), so each screen keeps its own value; the others become their plain value.
    val setByStyles = files.filter { it.startsWith("res/values") }.flatMap { path ->
        val file = get(path)
        if (file.isFile) LG_STYLE_ITEM_ID.findAll(file.readText()).map { "?0x" + it.groupValues[1].lowercase() }.toList() else emptyList()
    }.toSet()
    val readByXml = files.flatMap { path ->
        val file = get(path)
        if (file.isFile) LG_REFERENCE.findAll(file.readText()).filter { it.groupValues[1] == "?" }
            .map { "?0x" + it.groupValues[2].lowercase() }.toList() else emptyList()
    }.toSet()
    val themeAttributes = (setByStyles intersect readByXml).filter { it in LG_FRAMEWORK_VALUES }
        .associateWith { "lg_" + LG_FRAMEWORK_VALUES.getValue(it).name }

    fun answer(key: String): String? {
        themeAttributes[key]?.let { return "?attr/$it" }
        extra[key]?.let { return it }
        LG_FRAMEWORK_ANSWERS[key]?.let { return it }
        val value = LG_FRAMEWORK_VALUES[key] ?: return null
        if (value.night == null) return value.day
        colors[value.name] = value
        return "@color/lg_${value.name}"
    }

    // Resources are decoded on request: list them, read each XML, rewrite only the ones that name the framework.
    for (path in files) {
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        if (!LG_REFERENCE.containsMatchIn(text) && !LG_ATTRIBUTE.containsMatchIn(text) && !LG_STYLE_ITEM.containsMatchIn(text)) continue
        var changed = LG_STYLE_ITEM.replace(text) { match ->
            val attribute = themeAttributes["?0x" + LG_STYLE_ITEM_ID.find(match.value)!!.groupValues[1].lowercase()]
            if (attribute == null) "" else match.value.replace(Regex("""name="(\w+:)?r0x02[0-9a-f]{6}""""), """name="$attribute"""")
        }
        changed = LG_REFERENCE_ATTRIBUTE.replace(changed) { match ->
            val key = match.groupValues[1] + "0x" + match.groupValues[2].lowercase()
            if (answer(key) == "") {
                replaced.merge(key, 1, Int::plus)
                ""
            } else {
                match.value
            }
        }
        changed = LG_ATTRIBUTE.replace(changed) { match ->
            val convert = attributes["0x" + match.groupValues[2].lowercase()] ?: return@replace ""
            convert(match.groupValues[3])
        }
        changed = LG_REFERENCE.replace(changed) { match ->
            val key = match.groupValues[1] + "0x" + match.groupValues[2].lowercase()
            val answer = answer(key)
            if (answer == null) {
                unknown += "$key ($path)"
                match.value
            } else {
                replaced.merge(key, 1, Int::plus)
                answer
            }
        }
        // LG's namespace declaration, once nothing in the file uses it.
        LG_NAMESPACE.find(changed)?.let { ns ->
            if (!changed.contains(ns.groupValues[1] + ":")) changed = changed.replaceFirst(ns.value, "")
        }
        if (changed != text) file.writeText(changed)
    }
    if (unknown.isNotEmpty()) throw PatchException("LG framework references without an answer: ${unknown.joinToString()}")

    val themes = replaced.keys.any { LG_FRAMEWORK_ANSWERS[it]?.startsWith("@style/$LG_DAY_NIGHT") == true }
    if (themes) LG_THEME_COLORS.forEach { colors[it.name] = it }
    // The theme attributes' defaults (LG's values) in LgDayNight, declared as the app's own attributes.
    val defaults = themeAttributes.map { (key, attribute) ->
        val value = LG_FRAMEWORK_VALUES.getValue(key)
        if (value.night != null) colors[value.name] = value
        attribute to if (value.night != null) "@color/lg_${value.name}" else value.day
    }
    if (defaults.isNotEmpty()) {
        document("res/values/attrs.xml").use { xml ->
            for ((attribute, _) in defaults) {
                xml.documentElement.appendChild(xml.createElement("attr").apply {
                    setAttribute("name", attribute)
                    setAttribute("format", "reference|color|dimension")
                })
            }
        }
    }
    addLgColors(colors.values)
    if (themes) addLgDayNight(defaults)
    if (replaced.keys.any { LG_FRAMEWORK_ANSWERS[it]?.startsWith("@drawable/lg_floating_action_button") == true }) {
        addLgFloatingActionButton()
    }
    return replaced
}

/** LG's floating action button: an accent-coloured circle (54dp) with a white plus. */
private fun ResourcePatchContext.addLgFloatingActionButton() {
    get("res/drawable/lg_floating_action_button_background.xml").writeText(
        """<?xml version="1.0" encoding="utf-8"?>
        |<ripple xmlns:android="http://schemas.android.com/apk/res/android" android:color="?android:attr/colorControlHighlight">
        |    <item>
        |        <shape android:shape="oval">
        |            <solid android:color="?android:attr/colorAccent" />
        |            <size android:width="54dp" android:height="54dp" />
        |        </shape>
        |    </item>
        |</ripple>
        |""".trimMargin(),
    )
    get("res/drawable/lg_floating_action_button_add.xml").writeText(
        """<?xml version="1.0" encoding="utf-8"?>
        |<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"
        |    android:viewportWidth="24" android:viewportHeight="24">
        |    <path android:fillColor="#ffffffff" android:pathData="M19,13H13V19H11V13H5V11H11V5H13V11H19V13Z" />
        |</vector>
        |""".trimMargin(),
    )
}

/**
 * LG's dialog building blocks that code inflates by id (see [LG_FRAMEWORK_CODE_IDS]) or looks up by name (the runtime
 * LgRes.getIdentifier), with Android's ids as LG's have them: a message (text1), a check box row (checkbox), a hint and
 * a line (hint, text1), the scrolling frame they are added to (content), list rows, progress bodies, a first-use guide
 * and an alert (its title is [runtime]'s LgAlertTitle, a Java package: it carries Android's alertTitle id); plus the
 * sizes code asks for and the no-content hint's "+". Returns the layouts added.
 */
internal fun ResourcePatchContext.addLgFrameworkLayouts(runtime: String): Int {
    val layouts = mapOf(
        "lg_dialog_c_1" to """<TextView xmlns:android="http://schemas.android.com/apk/res/android" android:id="@android:id/text1"
            |    android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="start|center_vertical"
            |    android:paddingStart="24dp" android:paddingEnd="24dp" android:paddingTop="16dp" android:paddingBottom="8dp"
            |    android:textAlignment="viewStart" android:textAppearance="?android:attr/textAppearanceMedium" />""",
        "lg_dialog_c_11" to """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical"
            |    android:layout_width="match_parent" android:layout_height="wrap_content">
            |    <CheckBox android:id="@android:id/checkbox" android:layout_width="match_parent" android:layout_height="wrap_content"
            |        android:minHeight="48dp" android:layout_marginStart="20dp" android:layout_marginEnd="24dp"
            |        android:paddingStart="8dp" android:textAppearance="?android:attr/textAppearanceSmall" />
            |</LinearLayout>""",
        "lg_dialog_c_21" to """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical"
            |    android:layout_width="match_parent" android:layout_height="wrap_content">
            |    <TextView android:id="@android:id/hint" android:layout_width="match_parent" android:layout_height="wrap_content"
            |        android:paddingStart="24dp" android:paddingEnd="24dp" android:paddingTop="8dp"
            |        android:textAppearance="?android:attr/textAppearanceSmall" android:textColor="?android:attr/colorAccent" />
            |    <TextView android:id="@android:id/text1" android:layout_width="wrap_content" android:layout_height="48dp"
            |        android:gravity="center_vertical" android:paddingStart="24dp" android:paddingEnd="24dp"
            |        android:textAppearance="?android:attr/textAppearanceListItem" />
            |</LinearLayout>""",
        "lg_dialog_c_frame2" to """<ScrollView xmlns:android="http://schemas.android.com/apk/res/android" android:clipToPadding="false"
            |    android:layout_width="match_parent" android:layout_height="wrap_content">
            |    <LinearLayout android:id="@android:id/content" android:orientation="vertical"
            |        android:layout_width="match_parent" android:layout_height="wrap_content" />
            |</ScrollView>""",
        // Looked up by name (getIdentifier(name, type, "com.lge"), see the runtime LgRes): a two-line list row ...
        "lg_dialog_c_2" to """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical"
            |    android:layout_width="match_parent" android:layout_height="wrap_content" android:minHeight="56dp"
            |    android:gravity="center_vertical" android:paddingStart="24dp" android:paddingEnd="24dp"
            |    android:paddingTop="10dp" android:paddingBottom="10dp">
            |    <TextView android:id="@android:id/text1" android:layout_width="wrap_content" android:layout_height="wrap_content"
            |        android:textAppearance="?android:attr/textAppearanceListItem" />
            |    <TextView android:id="@android:id/text2" android:layout_width="wrap_content" android:layout_height="wrap_content"
            |        android:textAppearance="?android:attr/textAppearanceListItemSecondary" />
            |</LinearLayout>""",
        // ... a row with an icon, a line and an end icon ...
        "lg_dialog_c_4" to """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="horizontal"
            |    android:layout_width="match_parent" android:layout_height="wrap_content" android:minHeight="56dp"
            |    android:gravity="center_vertical" android:paddingStart="24dp" android:paddingEnd="24dp">
            |    <ImageView android:id="@android:id/icon1" android:layout_width="24dp" android:layout_height="24dp"
            |        android:layout_marginEnd="16dp" />
            |    <TextView android:id="@android:id/text1" android:layout_width="0dp" android:layout_height="wrap_content"
            |        android:layout_weight="1" android:textAppearance="?android:attr/textAppearanceListItem" />
            |    <ImageView android:id="@android:id/icon2" android:layout_width="24dp" android:layout_height="24dp"
            |        android:layout_marginStart="16dp" />
            |</LinearLayout>""",
        // ... a spinner with a message and a line ...
        "lg_dialog_c_15" to """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="horizontal"
            |    android:layout_width="match_parent" android:layout_height="wrap_content" android:gravity="center_vertical"
            |    android:padding="24dp">
            |    <ProgressBar android:id="@android:id/progress" android:layout_width="48dp" android:layout_height="48dp"
            |        android:layout_marginEnd="16dp" android:indeterminate="true" />
            |    <LinearLayout android:orientation="vertical" android:layout_width="match_parent" android:layout_height="wrap_content">
            |        <TextView android:id="@android:id/message" android:layout_width="wrap_content" android:layout_height="wrap_content"
            |            android:textAppearance="?android:attr/textAppearanceMedium" />
            |        <TextView android:id="@android:id/text1" android:layout_width="wrap_content" android:layout_height="wrap_content"
            |            android:textAppearance="?android:attr/textAppearanceSmall" />
            |    </LinearLayout>
            |</LinearLayout>""",
        // ... a message, two lines (count / percentage) and a horizontal progress bar ...
        "lg_dialog_c_22" to """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical"
            |    android:layout_width="match_parent" android:layout_height="wrap_content" android:padding="24dp">
            |    <TextView android:id="@android:id/message" android:layout_width="match_parent" android:layout_height="wrap_content"
            |        android:textAppearance="?android:attr/textAppearanceMedium" />
            |    <LinearLayout android:orientation="horizontal" android:layout_width="match_parent" android:layout_height="wrap_content"
            |        android:layout_marginTop="12dp">
            |        <TextView android:id="@android:id/text1" android:layout_width="0dp" android:layout_weight="1"
            |            android:layout_height="wrap_content" android:textAppearance="?android:attr/textAppearanceSmall" />
            |        <TextView android:id="@android:id/text2" android:layout_width="wrap_content" android:layout_height="wrap_content"
            |            android:textAppearance="?android:attr/textAppearanceSmall" />
            |    </LinearLayout>
            |    <ProgressBar android:id="@android:id/progress" style="?android:attr/progressBarStyleHorizontal"
            |        android:layout_width="match_parent" android:layout_height="wrap_content" />
            |</LinearLayout>""",
        // ... a first-use guide (title, picture, message, "do not show again", OK) ...
        "lg_dialog_init_guide" to """<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:layout_width="match_parent" android:layout_height="wrap_content">
            |    <LinearLayout android:orientation="vertical" android:layout_width="match_parent" android:layout_height="wrap_content"
            |        android:paddingStart="24dp" android:paddingEnd="24dp" android:paddingTop="20dp" android:paddingBottom="8dp">
            |        <TextView android:id="@android:id/title" android:layout_width="match_parent" android:layout_height="wrap_content"
            |            android:textAppearance="?android:attr/textAppearanceLarge" android:paddingBottom="12dp" />
            |        <ImageView android:id="@android:id/icon" android:layout_width="match_parent" android:layout_height="wrap_content"
            |            android:adjustViewBounds="true" />
            |        <TextView android:id="@android:id/message" android:layout_width="match_parent" android:layout_height="wrap_content"
            |            android:paddingTop="12dp" android:textAppearance="?android:attr/textAppearanceMedium" />
            |        <FrameLayout android:id="@android:id/custom" android:layout_width="match_parent" android:layout_height="wrap_content" />
            |        <CheckBox android:id="@android:id/checkbox" android:layout_width="match_parent" android:layout_height="wrap_content"
            |            android:minHeight="48dp" android:layout_marginTop="8dp" />
            |        <Button android:id="@android:id/button1" style="?android:attr/buttonBarButtonStyle" android:layout_gravity="end"
            |            android:layout_width="wrap_content" android:layout_height="wrap_content" />
            |    </LinearLayout>
            |</ScrollView>""",
        // ... and an alert (icon and title, message, OK).
        "lg_alert_dialog_material" to """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="vertical"
            |    android:layout_width="match_parent" android:layout_height="wrap_content" android:paddingStart="24dp"
            |    android:paddingEnd="24dp" android:paddingTop="20dp" android:paddingBottom="8dp">
            |    <LinearLayout android:orientation="horizontal" android:gravity="center_vertical" android:layout_width="match_parent"
            |        android:layout_height="wrap_content">
            |        <ImageView android:id="@android:id/icon" android:layout_width="32dp" android:layout_height="32dp"
            |            android:layout_marginEnd="12dp" />
            |        <$runtime.LgAlertTitle android:layout_width="match_parent" android:layout_height="wrap_content"
            |            android:textAppearance="?android:attr/textAppearanceLarge" />
            |    </LinearLayout>
            |    <TextView android:id="@android:id/message" android:layout_width="match_parent" android:layout_height="wrap_content"
            |        android:paddingTop="12dp" android:textAppearance="?android:attr/textAppearanceMedium" />
            |    <FrameLayout android:id="@android:id/custom" android:layout_width="match_parent" android:layout_height="wrap_content" />
            |    <Button android:id="@android:id/button1" style="?android:attr/buttonBarButtonStyle" android:layout_gravity="end"
            |        android:layout_width="wrap_content" android:layout_height="wrap_content" />
            |</LinearLayout>""",
    )
    for ((name, body) in layouts) {
        get("res/layout/$name.xml").writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + body.trimMargin() + "\n")
    }
    // The "+" LG draws in a no-content hint ("Tap (+) to add"): a plus in a circle, in the accent colour.
    get("res/drawable/lg_no_contents_button_add.xml").writeText(
        """<?xml version="1.0" encoding="utf-8"?>
        |<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp"
        |    android:viewportWidth="24" android:viewportHeight="24" android:tint="?android:attr/colorAccent">
        |    <path android:fillColor="#ffffffff" android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM17,13h-4v4h-2v-4H7v-2h4V7h2v4h4v2z" />
        |</vector>
        |""".trimMargin(),
    )
    document("res/values/dimens.xml").use { xml ->
        for ((name, value) in listOf("lg_type_d01_sp" to "16.0sp", "lg_expand_action_bar_main_title_padding_bottom" to "9.5dp")) {
            xml.documentElement.appendChild(xml.createElement("dimen").apply { setAttribute("name", name); textContent = value })
        }
    }
    return layouts.size
}

/** Adds LG's colours as app colours "lg_<name>", with their night values in values-night. */
private fun ResourcePatchContext.addLgColors(colors: Collection<LgValue>) {
    if (colors.isEmpty()) return
    for ((path, night) in listOf("res/values/colors.xml" to false, "res/values-night/colors.xml" to true)) {
        val file = get(path)
        if (!file.isFile) {
            file.parentFile.mkdirs()
            file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
        }
        document(path).use { xml ->
            val have = xml.documentElement.children("color").map { it.getAttribute("name") }.toSet()
            for (color in colors) {
                if ("lg_${color.name}" in have) continue
                xml.documentElement.appendChild(xml.createElement("color").apply {
                    setAttribute("name", "lg_${color.name}")
                    textContent = if (night) color.night ?: color.day else color.day
                })
            }
        }
    }
}

/**
 * LG's default theme off LG's ROM: Android's day/night theme with LG's accent, bar and background colours, its
 * status and navigation bar icons following the mode, and the variants LG's apps use (no action bar, full screen,
 * over the wallpaper, alert dialogs).
 */
private fun ResourcePatchContext.addLgDayNight(attributes: List<Pair<String, String>>) {
    fun add(xml: app.morphe.patcher.util.Document, name: String, parent: String, items: List<Pair<String, String>>) {
        if (xml.documentElement.children("style").any { it.getAttribute("name") == name }) return
        xml.documentElement.appendChild(xml.createElement("style").apply {
            setAttribute("name", name)
            setAttribute("parent", parent)
            for ((key, value) in items) {
                appendChild(xml.createElement("item").apply { setAttribute("name", key); textContent = value })
            }
        })
    }
    document("res/values/styles.xml").use { xml ->
        add(
            xml, LG_DAY_NIGHT, "@android:style/Theme.DeviceDefault.DayNight",
            listOf(
                "android:colorAccent" to "@color/lg_color_accent_ui",
                "android:colorPrimary" to "@color/lg_foreground_material_light",
                "android:colorPrimaryDark" to "@color/lg_foreground_material_light",
                "android:statusBarColor" to "@color/lg_foreground_material_light",
                "android:navigationBarColor" to "@color/lg_foreground_material_light",
                "android:colorBackground" to "@color/lg_background_material_light",
                "android:colorBackgroundFloating" to "@color/lg_background_material_light",
                // LG's colorForeground is its surface colour (light grey by day, near black by night), which LG's apps
                // paint their screens with; Android's is the opposite (a text-like colour).
                "android:colorForeground" to "@color/lg_foreground_material_light",
                "android:textColorPrimary" to "@color/lg_primary_text_default_material_light",
                "android:textColorSecondary" to "@color/lg_secondary_text_default_material_light",
                "android:windowBackground" to "@color/lg_background_material_light",
                "android:colorControlNormal" to "@color/lg_color_control_normal_ui",
                "android:windowLightStatusBar" to "?android:attr/isLightTheme",
                "android:windowLightNavigationBar" to "?android:attr/isLightTheme",
                // LG's theme sets this one list attribute that Android's themes leave unset (apps' rows use it).
                "android:dividerHeight" to "1.0px",
            ) + attributes,
        )
        add(xml, "$LG_DAY_NIGHT.NoActionBar", "@style/$LG_DAY_NIGHT", listOf("android:windowActionBar" to "false", "android:windowNoTitle" to "true"))
        add(xml, "$LG_DAY_NIGHT.NoActionBar.Fullscreen", "@style/$LG_DAY_NIGHT.NoActionBar", listOf("android:windowFullscreen" to "true"))
        add(
            xml, "$LG_DAY_NIGHT.Wallpaper", "@style/$LG_DAY_NIGHT",
            listOf("android:windowShowWallpaper" to "true", "android:windowBackground" to "@android:color/transparent"),
        )
        add(xml, "$LG_DAY_NIGHT.Dialog.Alert", "@android:style/Theme.DeviceDefault.Light.Dialog.Alert", listOf("android:colorAccent" to "@color/lg_color_accent_ui"))
    }
    val night = get("res/values-night/styles.xml")
    if (!night.isFile) {
        night.parentFile.mkdirs()
        night.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
    }
    document("res/values-night/styles.xml").use { xml ->
        add(xml, "$LG_DAY_NIGHT.Dialog.Alert", "@android:style/Theme.DeviceDefault.Dialog.Alert", listOf("android:colorAccent" to "@color/lg_color_accent_ui"))
    }
}
