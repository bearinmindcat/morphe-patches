package org.ungoogled.patches.honorweather.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.honor.MAGIC_DAY_NIGHT
import org.ungoogled.patches.honor.MAGIC_DAY_NIGHT_NO_ACTION_BAR
import org.ungoogled.patches.honor.followMagicDayNight
import org.ungoogled.patches.honor.replaceMagicFrameworkIds
import org.ungoogled.patches.honor.replaceMagicFrameworkReferences
import org.ungoogled.patches.honor.replaceMagicIconDrawables
import org.ungoogled.patches.honor.replaceMagicInterpolators
import org.ungoogled.patches.honorweather.HonorWeatherConstants.COMPATIBILITY_HONOR_WEATHER
import org.ungoogled.patches.samsung.redirectCalls

internal const val HONOR_WEATHER_EXTENSION = "extensions/honorweather.mpe"
internal const val EXT = "Lorg/ungoogled/honorweather"

internal val log = java.util.logging.Logger.getLogger("HonorWeather")

/**
 * MagicOS framework icons Weather's own icon drawables point at (see replaceMagicIconDrawables), as the app's own
 * drawables: the same icon where the app carries a copy, else the app's closest one.
 */
private val WEATHER_ICON_SOURCES = mapOf(
    "@0x02030818" to "icsvg_public_location_filled", // icsvg_public_location_filled (same paths)
    "@0x020307b1" to "icsvg_public_comments_filled", // icsvg_public_comments_filled (same paths)
    "@0x02030771" to "icsvg_public_toolbar_add", // icsvg_public_add
    "@0x0203080d" to "ic_widget_card_home_14dp", // icsvg_public_home_filled
    "@0x02030348" to "ic_widget_card_gps_location_fail_14dp", // icsvg_hnlauncher_location_fail_filled
    "@0x020308c8" to "icsvg_alarm_tips", // icsvg_public_tips_filled
)

/** What Weather's XML takes from MagicOS's framework beyond the shared table (see replaceMagicFrameworkReferences). */
private val WEATHER_ANSWERS = mapOf(
    "?0x02010013" to "@android:style/Widget.DeviceDefault.Button.Colored", // attr/buttonStyleEmphasize
    "?0x020100ef" to "sans-serif", // attr/textFontFamilyRegular
    "?0x020100f0" to "sans-serif-medium", // attr/textFontFamilyMedium
    "@0x02030033" to "@drawable/$MAGIC_ARROW", // drawable/ic_public_arrow_right (see magicArrow)
    "@0x02050178" to "#ffffffff", // color/magic_white
    "@0x02050184" to "#ff000000", // color/magic_black
    "@0x020501a7" to "#ff808080", // color/magic_color_gray_7
    "@0x020600cd" to "@android:style/Theme.DeviceDefault", // style/Theme.Magic.Dark
    // Parents of Weather's own themes: MagicOS's day/night themes as Android's (followMagicDayNight adds the styles).
    "@0x02060008" to "@style/$MAGIC_DAY_NIGHT", // style/Theme.Magic
    "@0x02060009" to "@style/$MAGIC_DAY_NIGHT_NO_ACTION_BAR", // style/Theme.Magic.NoActionBar
    "@0x020600ce" to "@android:style/Theme.DeviceDefault.NoActionBar", // style/Theme.Magic.Dark.NoActionBar
    "@0x020601b3" to "@android:style/Widget.Material.SearchView", // style/Widget.Magic.Emphasize.SearchView.PatternSpinnerOut
)

private const val MAGIC_ARROW = "magic_ic_public_arrow_right"

/**
 * MagicOS's list-row arrow (ic_public_arrow_right: its next12 icon in the theme's secondary colour, mirrored in
 * right-to-left layouts): the app's copy of that icon's paths as a plain vector in Android's tertiary text colour, so
 * it shows on light and dark rows alike (the app's own white arrow vanished on light ones).
 */
private fun app.morphe.patcher.patch.ResourcePatchContext.magicArrow() {
    val source = get("res/drawable/icsvg_public_next12_regular.xml")
    if (!source.isFile) throw PatchException("no drawable/icsvg_public_next12_regular")
    val text = source.readText()
    val start = text.indexOf('>', text.indexOf("<drawable")) + 1
    val end = text.lastIndexOf("</drawable>")
    if (start <= 0 || end < start) throw PatchException("drawable/icsvg_public_next12_regular is not an icon")
    val body = text.substring(start, end).replace("\"@color/ic_layer_01\"", "\"?android:attr/textColorTertiary\"")
    get("res/drawable/$MAGIC_ARROW.xml").writeText(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"" +
            " android:width=\"12dp\" android:height=\"24dp\" android:viewportWidth=\"48\" android:viewportHeight=\"96\"" +
            " android:autoMirrored=\"true\">$body</vector>\n",
    )
}

private const val DIALOG_DAY_NIGHT = "MagicDialogDayNight"

/**
 * The weather screen's theme is AppCompat's dark one (its pages draw their own sky), so its alert dialogs (location
 * permission notice, home city, ...) were dark ones in light mode too, around content the app inflates in MagicOS's
 * day/night theme. Its dialogs get Android's alert dialog theme of the phone's mode instead, as MagicOS's follow it.
 */
private fun app.morphe.patcher.patch.ResourcePatchContext.weatherHomeDialogs() {
    for ((path, parent) in listOf(
        "res/values/styles.xml" to "@android:style/Theme.DeviceDefault.Light.Dialog.Alert",
        "res/values-night/styles.xml" to "@android:style/Theme.DeviceDefault.Dialog.Alert",
    )) {
        if (!get(path).isFile) throw PatchException("no $path")
        document(path).use { xml ->
            val style = xml.createElement("style")
            style.setAttribute("name", DIALOG_DAY_NIGHT)
            style.setAttribute("parent", parent)
            xml.documentElement.appendChild(style)
        }
    }
    document("res/values/styles.xml").use { xml ->
        val styles = xml.getElementsByTagName("style")
        val home = (0 until styles.length).map { styles.item(it) as org.w3c.dom.Element }
            .firstOrNull { it.getAttribute("name") == "Theme.WeatherHome" } ?: throw PatchException("no style Theme.WeatherHome")
        home.appendChild(xml.createElement("item").apply {
            setAttribute("name", "android:alertDialogTheme")
            textContent = "@style/$DIALOG_DAY_NIGHT"
        })
    }
}

private val magicResourcesPatch = resourcePatch(
    description = "Gives Honor Weather the icons, sizes, fonts and animation curves MagicOS would.",
) {
    execute {
        val icons = replaceMagicIconDrawables(WEATHER_ICON_SOURCES)
        log.info("MagicOS icon drawables: $icons")
        if (icons < 70) throw PatchException("only $icons MagicOS icon drawables found")

        magicArrow()

        val interpolators = replaceMagicInterpolators()
        log.info("MagicOS interpolators: $interpolators")
        if (interpolators < 1) throw PatchException("no MagicOS interpolators found")

        val replaced = replaceMagicFrameworkReferences(WEATHER_ANSWERS)
        log.info("MagicOS framework references: $replaced")
        if (replaced.size < 30) throw PatchException("only ${replaced.size} MagicOS framework references found")

        val (activities, styles) = followMagicDayNight()
        log.info("Day/night themes: $activities activities, $styles styles")
        weatherHomeDialogs()
    }
}

/**
 * MagicOS framework resources Weather's code names by id: theme attributes its own resources stand for (its sizes
 * table maps each of its androidhnext_attr_* dimensions to the framework attribute and resolves that; off MagicOS the
 * answer was 0 and the pressure card died inflating), Theme.Magic for its web pages, MagicOS's "failed" icon; the ad
 * and game-center SDKs' (dormant) and two numbers that only look like ids keep their literals.
 */
private val WEATHER_CODE_ANSWERS = mapOf(
    0x020100c8 to "dimen/androidhnext_attr_max_padding_start", // attr/maxPaddingStart
    0x020100c9 to "dimen/androidhnext_attr_default_padding_end", // attr/defaultPaddingEnd
    0x020100ca to "dimen/androidhnext_attr_max_padding_end", // attr/maxPaddingEnd
    0x020100cd to "dimen/androidhnext_attr_defaultPaddingBottomFixed", // attr/defaultPaddingBottomFixed
    0x020100e2 to "dimen/androidhnext_attr_text_size_headline7", // attr/textSizeHeadline7
    0x020100e3 to "dimen/androidhnext_attr_text_size_subtitle1", // attr/textSizeSubTitle1
    0x020100e4 to "dimen/androidhnext_attr_text_size_subtitle2", // attr/textSizeSubTitle2
    0x020100e5 to "dimen/androidhnext_attr_text_size_subtitle3", // attr/textSizeSubTitle3
    0x020100e6 to "dimen/androidhnext_attr_text_size_button1", // attr/textSizeButton1
    0x020100e7 to "dimen/androidhnext_attr_text_size_button2", // attr/textSizeButton2
    0x020100e8 to "dimen/androidhnext_attr_text_size_body1", // attr/textSizeBody1
    0x020100e9 to "dimen/androidhnext_attr_text_size_body2", // attr/textSizeBody2
    0x020100ea to "dimen/androidhnext_attr_text_size_body3", // attr/textSizeBody3
    0x020100f4 to "dimen/androidhnext_attr_defaultCornerRadiusM", // attr/defaultCornerRadiusM
    0x02030043 to "drawable/home_icon_header_failed", // drawable/ic_public_fail (a web page that did not load)
    0x02060008 to "0x010302e3", // style/Theme.Magic -> android:style/Theme.DeviceDefault.DayNight
    0x0206002b to "", // style/Theme.Magic.Dialog.Alert: game-center SDK pages (dormant)
    0x020e027e to "", // dimen/magic_dimens_max_start: ad SDK column layout (dormant)
    0x020e027f to "", // dimen/magic_dimens_max_end
    0x02014b50 to "", // a ZIP central directory signature, not an id
    0x02073d29 to "", // media3 codec table numbers, not ids
    0x020c0ddb to "",
    0x02200000 to "", // a media3 frame size (H.264 level), not an id
)

private val magicCodePatch = bytecodePatch(
    description = "Gives Honor Weather's code the sizes and themes MagicOS would.",
) {
    extendWith(HONOR_WEATHER_EXTENSION)

    execute {
        val replaced = replaceMagicFrameworkIds("$EXT/Magic;", WEATHER_CODE_ANSWERS)
        log.info("MagicOS framework ids in code: $replaced")
        if (replaced.size < 15) throw PatchException("only ${replaced.size} MagicOS framework ids in code")
    }
}

private object CityListCreateFingerprint : Fingerprint(
    definingClass = "Lcom/hihonor/android/totemweather/city/ManageCityActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

/**
 * The city list casts the window's action bar to MagicOS's toolbar class (ClassCastException off MagicOS, the list
 * did not open); Weather.magicActionBar makes that action bar the app's own HwToolbar, a Toolbar subclass.
 */
private val actionBarPatch = bytecodePatch(
    description = "Gives Honor Weather's city list the toolbar class MagicOS would.",
) {
    extendWith(HONOR_WEATHER_EXTENSION)

    execute {
        CityListCreateFingerprint.method.addInstructions(0, "invoke-static/range {p0 .. p0}, $EXT/Weather;->magicActionBar(Landroid/app/Activity;)V")
    }
}

private val SETTINGS_SHAPES = listOf("Int" to "I", "Long" to "J", "Float" to "F").flatMap { (name, type) ->
    listOf(
        "get$name(Landroid/content/ContentResolver;Ljava/lang/String;$type)$type",
        "get$name(Landroid/content/ContentResolver;Ljava/lang/String;)$type",
        "put$name(Landroid/content/ContentResolver;Ljava/lang/String;$type)Z",
    )
} + listOf(
    "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;",
    "putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z",
)

/**
 * On MagicOS Weather is a system app that keeps some of its state in the phone's settings; elsewhere those writes are
 * refused (SecurityException at start-up). MagicSettings keeps them in the app's own preferences.
 */
private val settingsPatch = bytecodePatch(
    description = "Keeps Honor Weather's own settings in the app.",
) {
    extendWith(HONOR_WEATHER_EXTENSION)

    execute {
        val calls = buildMap {
            for (table in listOf("Secure", "System", "Global")) {
                for (shape in SETTINGS_SHAPES) {
                    val name = shape.substringBefore('(')
                    put(
                        "Landroid/provider/Settings\$$table;->$shape",
                        "$EXT/MagicSettings;->${table.lowercase()}${name.replaceFirstChar(Char::uppercase)}${shape.substring(shape.indexOf('('))}",
                    )
                }
            }
        }
        val moved = redirectCalls(calls)
        log.info("Settings calls: $moved")
        // Measured against 18.1.2.311: 39 writes among 180 calls.
        if (moved < 100) throw PatchException("only $moved Settings calls found")
    }
}

/**
 * Off MagicOS Weather dies at once on com.hihonor.android.os.SystemPropertiesEx (a MagicOS framework class). The
 * extension carries stand-ins for the MagicOS classes it uses (generated by .github/scripts/honor-standins); on an Honor
 * phone the framework's own classes load first, so they never run there.
 */
@Suppress("unused")
val enableNonMagicOsPatch = bytecodePatch(
    name = "Enable non-MagicOS ROM support",
    description = "Runs Honor Weather on non-Honor phones with forecasts from Open-Meteo.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HONOR_WEATHER)
    dependsOn(magicResourcesPatch, magicCodePatch, actionBarPatch, settingsPatch, openMeteoPatch, honorSdksPatch)
    extendWith(HONOR_WEATHER_EXTENSION)
}
