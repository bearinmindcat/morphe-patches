package org.ungoogled.patches.honor

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext

/**
 * What MagicOS's framework resources (the androidhnext package, id 0x02, which only Honor's ROM has) answer, where the
 * answer is a plain value on every phone configuration: the theme attributes of MagicOS's default theme (Theme.Magic,
 * "?0x0201...") and framework resources ("@0x02...") Honor's apps reference, read from androidhnext 14 (MagicOS 8) by
 * .github/scripts/honor-standins/magicres.py. Their ids are public, so they hold for every MagicOS version. Colours
 * that differ in night mode, drawables, styles and fonts are not here: a port gives those its own answer.
 */
internal val MAGIC_FRAMEWORK_VALUES = mapOf(
    "?0x020100c7" to "12.0dp", // attr/defaultPaddingStart
    "?0x020100c8" to "24.0dp", // attr/maxPaddingStart
    "?0x020100c9" to "12.0dp", // attr/defaultPaddingEnd
    "?0x020100ca" to "24.0dp", // attr/maxPaddingEnd
    "?0x020100cb" to "24.0dp", // attr/defaultPaddingTop
    "?0x020100cd" to "24.0dp", // attr/defaultPaddingBottomFixed
    "?0x020100ce" to "12.0dp", // attr/cardMarginStart
    "?0x020100cf" to "12.0dp", // attr/cardMarginEnd
    "?0x020100d0" to "8.0dp", // attr/cardMarginMiddle
    "?0x020100d1" to "2.0dp", // attr/textMarginVertical
    "?0x020100d2" to "8.0dp", // attr/textMarginHorizontal
    "?0x020100d3" to "48.0dp", // attr/textParagraphMarginXL
    "?0x020100d4" to "24.0dp", // attr/textParagraphMarginL
    "?0x020100d5" to "16.0dp", // attr/textParagraphMarginM
    "?0x020100d6" to "8.0dp", // attr/textParagraphMarginS
    "?0x020100d7" to "4.0dp", // attr/textParagraphMarginXS
    "?0x020100d8" to "16.0dp", // attr/elementsMarginVerticalL
    "?0x020100d9" to "8.0dp", // attr/elementsMarginVerticalM
    "?0x020100da" to "16.0dp", // attr/elementsMarginHorizontalL
    "?0x020100dd" to "72.0dp", // attr/textSizeHeadline2
    "?0x020100de" to "60.0dp", // attr/textSizeHeadline3
    "?0x020100df" to "48.0dp", // attr/textSizeHeadline4
    "?0x020100e0" to "38.0dp", // attr/textSizeHeadline5
    "?0x020100e1" to "30.0sp", // attr/textSizeHeadline6
    "?0x020100e2" to "20.0sp", // attr/textSizeHeadline7
    "?0x020100e3" to "18.0sp", // attr/textSizeSubTitle1
    "?0x020100e4" to "16.0sp", // attr/textSizeSubTitle2
    "?0x020100e5" to "14.0sp", // attr/textSizeSubTitle3
    "?0x020100e6" to "16.0sp", // attr/textSizeButton1
    "?0x020100e7" to "14.0sp", // attr/textSizeButton2
    "?0x020100e8" to "16.0sp", // attr/textSizeBody1
    "?0x020100e9" to "14.0sp", // attr/textSizeBody2
    "?0x020100ea" to "12.0sp", // attr/textSizeBody3
    "?0x020100eb" to "10.0dp", // attr/textSizeCaption
    "?0x020100f3" to "16.0dp", // attr/defaultCornerRadiusL
    "?0x020100f4" to "12.0dp", // attr/defaultCornerRadiusM
    "?0x020100f5" to "8.0dp", // attr/defaultCornerRadiusS
    "?0x02010116" to "24.0sp", // attr/textSizeHeadline8
    "?0x02010117" to "10.0sp", // attr/textSizeCaption1
    "?0x02010154" to "48.0dp", // attr/elementsMarginVerticalXXL
    "?0x02010158" to "24.0dp", // attr/elementsMarginVerticalL2
    "?0x0201015a" to "12.0dp", // attr/elementsMarginVerticalM2
    "?0x0201015d" to "4.0dp", // attr/elementsMarginHorizontalS
    "?0x0201015e" to "2.0dp", // attr/elementsMarginVerticalXS
    "@0x020501a7" to "#ff808080", // color/magic_color_gray_7
    "@0x020e0081" to "8.0dp", // dimen/preferrence_padding_magic
    "@0x020e0119" to "24.0dp", // dimen/padding_xl
    "@0x020e011a" to "16.0dp", // dimen/padding_l
    "@0x020e011b" to "8.0dp", // dimen/padding_m
    "@0x020e011c" to "4.0dp", // dimen/padding_s
    "@0x020e011d" to "2.0dp", // dimen/padding_xs
    "@0x020e011e" to "24.0dp", // dimen/margin_xl
    "@0x020e011f" to "16.0dp", // dimen/margin_l
    "@0x020e0120" to "8.0dp", // dimen/margin_m
    "@0x020e0121" to "4.0dp", // dimen/margin_s
    "@0x020e0122" to "2.0dp", // dimen/margin_xs
    "@0x020e0123" to "30.0sp", // dimen/magic_master_display_1
    "@0x020e0124" to "36.0sp", // dimen/magic_master_display_2
    "@0x020e0125" to "45.0dp", // dimen/magic_master_display_3
    "@0x020e0126" to "80.0dp", // dimen/magic_master_display_4
    "@0x020e0127" to "100.0dp", // dimen/magic_master_display_5
    "@0x020e0128" to "18.0sp", // dimen/magic_master_title_1
    "@0x020e0129" to "24.0sp", // dimen/magic_master_title_2
    "@0x020e012a" to "15.0sp", // dimen/magic_master_subtitle
    "@0x020e012b" to "12.0sp", // dimen/magic_master_body_1
    "@0x020e012c" to "13.0sp", // dimen/magic_master_body_2
    "@0x020e012d" to "9.0sp", // dimen/magic_master_caption_1
    "@0x020e012e" to "10.0sp", // dimen/magic_master_caption_2
)

// "??0x..." / "@@0x..." is how a "dynamic" reference (aapt2's encoding for a shared library's ids) decodes.
private val MAGIC_REFERENCE = Regex("""([?@])\1?0x(02[0-9a-f]{6})""", RegexOption.IGNORE_CASE)

/**
 * Off MagicOS there is no androidhnext package: a layout or style value that names one of its theme attributes fails
 * to inflate ("Failed to resolve attribute"), and one that names one of its resources has nothing behind it. Replaces
 * every such reference in the app's XML resources (layouts, drawables, ... and the styles of its resource table) with
 * what MagicOS itself answers ([MAGIC_FRAMEWORK_VALUES], then [extra], "?0x0201..." / "@0x02..." to a value or one of
 * the app's own resources). A reference neither knows stops the patch, listed, so a new port sees what to add.
 * Returns the references replaced, per framework id.
 */
internal fun ResourcePatchContext.replaceMagicFrameworkReferences(extra: Map<String, String> = emptyMap()): Map<String, Int> {
    val answers = MAGIC_FRAMEWORK_VALUES + extra
    val replaced = sortedMapOf<String, Int>()
    val unknown = sortedSetOf<String>()
    // Resources are decoded on request: list them, read each XML, rewrite only the ones that name the framework.
    for (path in listDecodableFiles("res/**").filter { it.endsWith(".xml") }) {
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        if (!MAGIC_REFERENCE.containsMatchIn(text)) continue
        val changed = MAGIC_REFERENCE.replace(text) { match ->
            val key = match.groupValues[1] + "0x" + match.groupValues[2].lowercase()
            val answer = answers[key]
            if (answer == null) {
                unknown += "$key ($path)"
                match.value
            } else {
                replaced.merge(key, 1, Int::plus)
                answer
            }
        }
        if (changed != text) file.writeText(changed)
    }
    if (unknown.isNotEmpty()) throw PatchException("MagicOS framework references without an answer: ${unknown.joinToString()}")
    return replaced
}

private val CUBIC_BEZIER = Regex("""<cubicBezierInterpolator\b([^>]*?)/>""")
private fun attribute(attributes: String, name: String) =
    Regex("""android:$name="([^"]*)"""").find(attributes)?.groupValues?.get(1) ?: "0"

/**
 * Honor's apps define interpolators with MagicOS's own `<cubicBezierInterpolator>` tag (control points in
 * android:fromXDelta/fromYDelta and android:toXDelta/toYDelta), which only MagicOS's AnimationUtils knows: elsewhere
 * loading one fails ("Unknown interpolator name"). Rewrites each as Android's `<pathInterpolator>` with the same
 * control points. Returns the files changed.
 */
internal fun ResourcePatchContext.convertMagicInterpolators(): Int {
    var files = 0
    for (path in listDecodableFiles("res/**").filter { it.endsWith(".xml") && (it.contains("/interpolator") || it.contains("/anim")) }) {
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        if (!CUBIC_BEZIER.containsMatchIn(text)) continue
        file.writeText(
            CUBIC_BEZIER.replace(text) { match ->
                val a = match.groupValues[1]
                val namespaces = Regex("""xmlns:\w+="[^"]*"""").findAll(a).joinToString("") { it.value + " " }
                """<pathInterpolator $namespaces""" + """android:controlX1="${attribute(a, "fromXDelta")}" android:controlY1="${attribute(a, "fromYDelta")}" """ +
                    """android:controlX2="${attribute(a, "toXDelta")}" android:controlY2="${attribute(a, "toYDelta")}"/>"""
            },
        )
        files++
    }
    return files
}

/**
 * MagicOS's LayoutInflater makes its own subclass of a plain widget a layout names (an <ImageView> becomes a
 * com.hihonor.android.widget.ImageView, ...), and Honor's apps cast to those classes: off MagicOS that cast failed
 * (ClassCastException). Rewrites the plain tags in the app's layouts to the MagicOS class ([widgets]: tag to class;
 * the stand-ins extend Android's own widget). Returns the layout files changed.
 */
internal fun ResourcePatchContext.useMagicWidgets(widgets: Map<String, String>): Int {
    val tag = Regex("""(</?)(${widgets.keys.joinToString("|")})([\s/>])""")
    var files = 0
    for (path in listDecodableFiles("res/layout*/*.xml")) {
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        val changed = tag.replace(text) { m -> m.groupValues[1] + widgets.getValue(m.groupValues[2]) + m.groupValues[3] }
        if (changed != text) {
            file.writeText(changed)
            files++
        }
    }
    return files
}
