package org.ungoogled.patches.honorhome.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.coloroslauncher.misc.redirectHiddenMembers
import org.ungoogled.patches.honor.followMagicDayNight
import org.ungoogled.patches.honor.redirectMagicFrameworkLookups
import org.ungoogled.patches.honor.replaceMagicFrameworkIds
import org.ungoogled.patches.honor.replaceMagicFrameworkReferences
import org.ungoogled.patches.honor.replaceMagicInterpolators
import org.ungoogled.patches.honorhome.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.honorhome.HIDDEN_CONSTRUCTORS_16_17
import org.ungoogled.patches.honorhome.HIDDEN_MEMBERS
import org.ungoogled.patches.honorhome.HIDDEN_MEMBERS_16_17
import org.ungoogled.patches.honorhome.HonorHomeConstants.COMPATIBILITY_HONOR_HOME
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.rewriteStringLiterals

internal const val HONOR_HOME_EXTENSION = "extensions/honorhome.mpe"

private const val EXT = "Lorg/ungoogled/honorhome"

private val log = java.util.logging.Logger.getLogger("HonorHome")

private val SETTINGS_SHAPES = listOf(
    "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;" to "GetString",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I" to "GetInt",
    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I" to "GetInt",
    "getLong(Landroid/content/ContentResolver;Ljava/lang/String;J)J" to "GetLong",
    "getLong(Landroid/content/ContentResolver;Ljava/lang/String;)J" to "GetLong",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)F" to "GetFloat",
    "getFloat(Landroid/content/ContentResolver;Ljava/lang/String;)F" to "GetFloat",
    "putString(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z" to "PutString",
    "putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z" to "PutInt",
    "putLong(Landroid/content/ContentResolver;Ljava/lang/String;J)Z" to "PutLong",
    "putFloat(Landroid/content/ContentResolver;Ljava/lang/String;F)Z" to "PutFloat",
)

/** MagicOS's per-user settings calls (SettingsEx), with the same shapes plus a user id. */
private val SETTINGS_EX_SHAPES = listOf(
    "Secure" to ("getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I" to "GetIntForUser"),
    "Secure" to ("getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;" to "GetStringForUser"),
    "Secure" to ("putStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;I)Z" to "PutStringForUser"),
    "System" to ("getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I" to "GetIntForUser"),
    "System" to ("putIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)Z" to "PutIntForUser"),
)

/** Every settings call goes through LocalSettings, which keeps a refused write in the app's own preferences. */
private val SETTINGS_CALLS: Map<String, String> = buildMap {
    for (table in listOf("Global", "Secure", "System")) {
        for ((shape, suffix) in SETTINGS_SHAPES) {
            put(
                "Landroid/provider/Settings\$$table;->$shape",
                "$EXT/LocalSettings;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}",
            )
        }
    }
    for ((table, call) in SETTINGS_EX_SHAPES) {
        val (shape, suffix) = call
        put(
            "Lcom/hihonor/android/provider/SettingsEx\$$table;->$shape",
            "$EXT/LocalSettings;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}",
        )
    }
}

/** Widget binds: refused ones open Android's widget permission dialog (see Widgets). */
private val WIDGET_BINDS = listOf(
    "(ILandroid/content/ComponentName;)Z",
    "(ILandroid/content/ComponentName;Landroid/os/Bundle;)Z",
    "(ILandroid/os/UserHandle;Landroid/content/ComponentName;Landroid/os/Bundle;)Z",
).associate { shape ->
    "Landroid/appwidget/AppWidgetManager;->bindAppWidgetIdIfAllowed$shape" to
        "$EXT/Widgets;->bind(Landroid/appwidget/AppWidgetManager;${shape.substring(1)}"
}

/**
 * What MagicOS's theme answers for Honor Home's references without a plain value: colours with night variants, fonts,
 * icons and styles, from the app's own copies (its bundled Honor UI kit has MagicOS's names and night variants) or
 * Android's nearest.
 */
private val HOME_ANSWERS = mapOf(
    // Plain values the shared table does not have yet (androidhnext 14, via magicres.py).
    "?0x020100db" to "8.0dp", // attr/elementsMarginHorizontalM
    "?0x020100f2" to "8.0dp", // attr/iconCornerRadius
    "?0x020100f6" to "4.0dp", // attr/defaultCornerRadiusXS
    "?0x02010159" to "24.0dp", // attr/elementsMarginHorizontalL2
    "?0x0201015b" to "12.0dp", // attr/elementsMarginHorizontalM2
    "?0x0201015c" to "4.0dp", // attr/elementsMarginVerticalS
    "?0x0201015f" to "2.0dp", // attr/elementsMarginHorizontalXS
    "?0x02010013" to "@android:style/Widget.DeviceDefault.Button.Colored", // attr/buttonStyleEmphasize
    "?0x02010074" to "@color/magic_clickeffic_default_color", // attr/clickEffectColor
    "?0x020100ef" to "sans-serif", // attr/textFontFamilyRegular
    "?0x020100f0" to "sans-serif-medium", // attr/textFontFamilyMedium ("HnChinese-medium" on MagicOS)
    "?0x020100f8" to "@color/magic_color_subheader_divider", // attr/colorSubheaderDivider
    "?0x02010101" to "@dimen/magic_tips_bg_alpha", // attr/tipsBackgroundAlpha
    "?0x02010103" to "@color/magic_functional_blue", // attr/textColorPrimaryActivated
    "?0x02010302" to "@color/magic_control_normal_2", // attr/colorControlNormal2
    "@0x02030033" to "@drawable/hwlistpattern_arrow_right", // drawable/ic_public_arrow_right
    "@0x02030797" to "@drawable/icsvg_public_calories_filled", // drawable/icsvg_public_calories_filled
    "@0x020307ab" to "@drawable/sport_type_icon", // drawable/icsvg_public_cloud_exercise_filled
    "@0x020500cd" to "@color/magic_color_divider_horizontal", // color/divider_horizontal_color_magic
    "@0x020500dc" to "#0d000000", // color/magic_black_color_alpha_5
    "@0x02050177" to "@color/magic_color_fg", // color/magic_primary
    "@0x02050178" to "#ffffffff", // color/magic_white
    "@0x02050184" to "#ff000000", // color/magic_black
    "@0x020501a0" to "@color/magic_accent", // color/magic_accent
    "@0x0206002b" to "@android:style/Theme.DeviceDefault.Light.Dialog.Alert", // style/Theme.Magic.Dialog.Alert
    "@0x020800ac" to "@anim/hwrecyclerview_cubic_bezier_interpolator_type_40_60", // interpolator/..._type_40_60
)

/** Honor Home's code names MagicOS framework resources by id: the app's own copies, Android's, or left alone. */
private val HOME_CODE_ANSWERS = mapOf(
    // Theme attributes the code resolves: the dimension MagicOS's theme answers with (the app has each one).
    0x020100c8 to "dimen/magic_dimens_max_start", // attr/maxPaddingStart
    0x020100c9 to "dimen/magic_dimens_default_end", // attr/defaultPaddingEnd
    0x020100ca to "dimen/magic_dimens_max_end", // attr/maxPaddingEnd
    0x020100cd to "dimen/magic_dimens_default_bottom_fixed", // attr/defaultPaddingBottomFixed
    0x020100d8 to "dimen/magic_dimens_element_vertical_large", // attr/elementsMarginVerticalL
    0x020100d9 to "dimen/magic_dimens_element_vertical_middle", // attr/elementsMarginVerticalM
    0x020100da to "dimen/magic_dimens_element_horizontal_large", // attr/elementsMarginHorizontalL
    0x020100db to "dimen/magic_dimens_element_horizontal_middle", // attr/elementsMarginHorizontalM
    0x020100f3 to "dimen/magic_corner_radius_large", // attr/defaultCornerRadiusL
    0x020100f4 to "dimen/magic_corner_radius_mediums", // attr/defaultCornerRadiusM
    0x0201011a to "dimen/magic_corner_radius_xlarge", // attr/defaultCornerRadiusXL
    0x0201015a to "dimen/magic_dimens_element_vertical_middle_2", // attr/elementsMarginVerticalM2
    0x0201015b to "dimen/magic_dimens_element_horizontal_middle_2", // attr/elementsMarginHorizontalM2
    0x0201015c to "dimen/magic_dimens_element_vertical_small", // attr/elementsMarginVerticalS
    0x0201015d to "dimen/magic_dimens_element_horizontal_small", // attr/elementsMarginHorizontalS
    0x0201015e to "dimen/magic_dimens_element_vertical_xsmall", // attr/elementsMarginVerticalXS
    // App twin (MagicOS's cloned apps): its label and restore toast; its storage dialog never runs off MagicOS.
    0x02020191 to "string/clone_app", // string/twinapp_label
    0x02020192 to "", // string/twinapp_default_storage_location_title
    0x02020193 to "", // string/twinapp_default_storage_location_message
    0x02020194 to "string/twinapp_data_restore_running", // string/twinapp_data_restore_running_toast
    0x0203002b to "", // drawable/background_magic: asked of MagicOS's theme engine only (ResourcesEx)
    0x0203137c to "drawable/icsvg_public_movelist_regular", // drawable/icsvg_public_movelist_regular
    0x0204000e to "", // array/anim_level_overlay: per-product animation levels, read in a try block
    0x0205007c to "0x0106000d", // color/navigationbar_magic_dark (#00000000) -> android:color/transparent
    0x0205009b to "0x0106000d", // color/colorful_magic (#00000000) -> android:color/transparent
    0x020501a6 to "color/magic_color_gray_6", // color/magic_color_gray_6
    0x02060008 to "0x010302e3", // style/Theme.Magic -> android:style/Theme.DeviceDefault.DayNight
    0x02060013 to "style/ThemeHoloLightWallpaperNotitler", // style/Theme.Magic.Wallpaper.NoTitleBar: the home's own
    0x02060189 to "0x01030128", // style/Theme.Magic.Dark.Emphasize -> android:style/Theme.DeviceDefault
    0x020601ae to "0x0", // style/Theme.Magic.Dialog.Alert.Positive -> the context's own alert dialog theme
    0x020601af to "0x01030129", // style/Theme.Magic.Dark.Emphasize.NoActionBar -> Theme.DeviceDefault.NoActionBar
    0x020601b2 to "0x0", // style/Theme.Magic.Emphasize.Dialog.Alert.Positive
    0x02061010 to "0x0", // style/Theme.Magic.Dialog -> the context's own dialog theme
    0x02080004 to "anim/hwrecyclerview_cubic_bezier_interpolator_type_33_33",
    0x02080006 to "anim/hwradiobutton_interpolator_type_20_90",
    0x020800ac to "anim/hwrecyclerview_cubic_bezier_interpolator_type_40_60",
    0x020800ad to "anim/cubic_bezier_interpolator_type_20_80",
    0x020a004a to "0x010a0000", // anim/wallpaper_close_enter -> android:anim/fade_in
    0x020a0051 to "0x010a0001", // anim/wallpaper_close_exit -> android:anim/fade_out
    0x020f0001 to "bool/config_largeHeap", // bool/single_hand_mode: off MagicOS there is none (a false bool)
    0x02100041 to "", // id/preference_magic_content: a padding lookup, null-checked
)

private const val MAGIC_PREFERENCE_ROW = "?androidhnext:layout/preference_magic"

/** A scene parameter whose value is a MagicOS framework interpolator id. */
private val SCENE_INTERPOLATOR = Regex("""val="(0x0208[0-9a-fA-F]{4})"""")

/** MagicOS's interpolators the scenes name, and the app's own copies. */
private val SCENE_INTERPOLATORS = mapOf(
    0x02080004 to "hwrecyclerview_cubic_bezier_interpolator_type_33_33", // interpolator/cubic_bezier_interpolator_type_33_33
    0x020800ad to "cubic_bezier_interpolator_type_20_80", // interpolator/cubic_bezier_interpolator_type_20_80
)

private const val DEFAULT_GRIDS = "4x4,4x5"
private const val MAGIC_GRIDS = "4x6,5x6"

/** The home activities of the drawer and simple-mode styles, enabled by Honor Home when the user picks that style. */
private val HOME_STYLES_OFF = setOf(
    "com.hihonor.android.launcher.drawer.DrawerLauncher",
    "com.hihonor.android.launcher.simpleui.SimpleUILauncher",
)

/**
 * MagicOS's theme values and interpolators, the day/night theme of its screens, the widget permission dialog's host
 * activity, and a badge authority of its own (so it installs beside the OnePlus System Launcher port).
 */
private val resourcesPatch = resourcePatch(
    description = "Gives Honor Home the sizes, colours, icons and day/night theme MagicOS would.",
) {
    execute {
        val interpolators = replaceMagicInterpolators()
        log.info("MagicOS interpolators: $interpolators")
        if (interpolators < 2) throw PatchException("only $interpolators MagicOS interpolators found")

        val replaced = replaceMagicFrameworkReferences(HOME_ANSWERS)
        log.info("MagicOS framework references: ${replaced.size} ids, ${replaced.values.sum()} uses")
        if (replaced.size < 40) throw PatchException("only ${replaced.size} MagicOS framework references found")

        // Animation scenes (res/xml/anim_*.xml, read by Honor's bundled hnanimscene library) give interpolators as
        // MagicOS framework ids in plain integer values, which the app loads as resources.
        var sceneValues = 0
        for (path in listDecodableFiles("res/xml/**").filter { it.endsWith(".xml") }) {
            val file = get(path)
            if (!file.isFile) continue
            val text = file.readText()
            val changed = SCENE_INTERPOLATOR.replace(text) { match ->
                val name = SCENE_INTERPOLATORS[match.groupValues[1].lowercase().removePrefix("0x").toInt(16)]
                    ?: throw PatchException("animation scene $path names MagicOS interpolator ${match.groupValues[1]}")
                sceneValues++
                "val=\"0x%08x\"".format(resourceId(ResourceType.ANIM, name).toInt())
            }
            if (changed != text) file.writeText(changed)
        }
        log.info("Animation scene interpolators: $sceneValues")
        // Measured against 14.0.0.210: 13 values in 3 scenes.
        if (sceneValues == 0) throw PatchException("no MagicOS interpolators in animation scenes")

        // The settings pages' title row includes MagicOS's own preference row by name (a string the inflater resolves
        // in the androidhnext package); the app's copy of Android's material preference row has the same parts.
        val titleRow = get("res/layout/preference_list_title_layout.xml")
        val titleText = if (titleRow.isFile) titleRow.readText() else ""
        if (MAGIC_PREFERENCE_ROW !in titleText) throw PatchException("settings title row no longer includes $MAGIC_PREFERENCE_ROW")
        titleRow.writeText(titleText.replace(MAGIC_PREFERENCE_ROW, "@layout/preference_material"))

        val (activities, styles) = followMagicDayNight()
        log.info("Day/night themes: $activities activities, $styles styles")

        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0)
                ?: throw PatchException("no <application> in the manifest")
            // One home activity per home screen style (standard, drawer, simple mode); Honor Home enables the chosen
            // one itself, MagicOS's setup enables the standard one. Android lists every enabled one, so out of the
            // box only the standard home answers Home.
            val activities = manifest.getElementsByTagName("activity")
            var styles = 0
            for (i in 0 until activities.length) {
                val a = activities.item(i) as org.w3c.dom.Element
                if (a.getAttribute("android:name") in HOME_STYLES_OFF) {
                    a.setAttribute("android:enabled", "false")
                    styles++
                }
            }
            if (styles != HOME_STYLES_OFF.size) throw PatchException("home styles found: $styles")
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", "org.ungoogled.honorhome.WidgetPermissionActivity")
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:excludeFromRecents", "true")
            activity.setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
            application.appendChild(activity)

            // The launcher provider also answers OPPO's badge authority (com.android.badge), which the OnePlus System
            // Launcher port declares as its own badge provider: two apps cannot both declare an authority, so the two
            // ports could not be installed together. Honor Home's own code never uses it (its badges go through
            // com.hihonor.android.launcher.settings); it gets a name of its own.
            val providers = manifest.getElementsByTagName("provider")
            var badge = 0
            for (i in 0 until providers.length) {
                val p = providers.item(i) as org.w3c.dom.Element
                val authorities = p.getAttribute("android:authorities").split(";")
                if (OPPO_BADGE_AUTHORITY !in authorities) continue
                p.setAttribute(
                    "android:authorities",
                    authorities.joinToString(";") { if (it == OPPO_BADGE_AUTHORITY) HONOR_BADGE_AUTHORITY else it },
                )
                badge++
            }
            // Measured against 14.0.0.210: one, com.hihonor.android.launcher.LauncherProvider.
            if (badge != 1) throw PatchException("providers with the $OPPO_BADGE_AUTHORITY authority: $badge")
        }
    }
}

private const val OPPO_BADGE_AUTHORITY = "com.android.badge"
private const val HONOR_BADGE_AUTHORITY = "com.hihonor.android.launcher.badge"

/**
 * System service calls that need a permission only the system holds (INTERACT_ACROSS_USERS); the member helper answers
 * the SecurityException with "absent" (user 0).
 */
private val REFUSED_CALLS = setOf(
    "Landroid/app/IActivityManager;->getCurrentUser()Landroid/content/pm/UserInfo;",
    "Landroid/app/ActivityManager;->getCurrentUser()I",
)

/**
 * Honor Home is a system app built against the full Android framework; an installed app may not link to its hidden
 * parts. Each hidden member it uses goes through Framework (used where Android allows it, "absent" elsewhere), each
 * `new` of a class an app may not construct through Framework.construct, and subclasses of such classes are rebased.
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(HONOR_HOME_EXTENSION)

    execute {
        val (calls, fields) = redirectHiddenMembers(
            "$EXT/Framework;", "$EXT/Hidden;",
            HIDDEN_MEMBERS + HIDDEN_MEMBERS_16_17 + REFUSED_CALLS + MAGIC_MEMBERS_WITHOUT_STAND_IN,
        )
        val constructors = redirectConstructors(
            "$EXT/Framework;", "$EXT/Hidden;", HIDDEN_CONSTRUCTORS + HIDDEN_CONSTRUCTORS_16_17,
        )
        val rebased = rebaseSubclasses(REBASES)
        skipCastOfNull("$EXT/Hidden;")
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; rebased classes: $rebased")
        if (calls < 100) throw PatchException("only $calls hidden member calls found")
        if (constructors == 0 || rebased == 0) throw PatchException("hidden constructors not found")
    }
}

/**
 * MagicOS members the stand-ins cannot carry, because their signatures name one of the app's own classes or a hidden
 * Android class: they answer "absent" through Framework too.
 */
private val MAGIC_MEMBERS_WITHOUT_STAND_IN = setOf(
    "Lcom/hihonor/android/app/ActivityManagerEx;->registerGameObserver(Lcom/hihonor/android/app/IGameObserver;)V",
    "Lcom/hihonor/android/app/ActivityManagerEx;->unregisterGameObserver(Lcom/hihonor/android/app/IGameObserver;)V",
    "Lcom/hihonor/android/app/HwActivityTaskManager;->getTaskSnapshot(IZ)Landroid/window/TaskSnapshot;",
    "Lcom/hihonor/android/view/HwTaskSnapshotWrapper;->getTaskSnapshot()Landroid/window/TaskSnapshot;",
    "Lcom/hihonor/android/view/HwWindowManager;->overridePendingAppTransitionRemote(Landroid/window/RemoteTransition;IIZ)Z",
)

private const val BINDER = "Landroid/os/Binder;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * Android classes Honor Home extends whose constructor an installed app may not call, or that Android 15 lacks: the
 * public class each subclass is rebased onto, and the interfaces it keeps.
 */
private val REBASES = mapOf(
    "Landroid/view/ISystemGestureExclusionListener\$Stub;" to (BINDER to listOf("Landroid/view/ISystemGestureExclusionListener;")),
    "Landroid/window/IBackRemoteTransition\$Stub;" to (BINDER to emptyList()),
    "Landroid/view/IRecentsAnimationController\$Default;" to (OBJECT to listOf("Landroid/view/IRecentsAnimationController;")),
    "Landroid/hardware/devicestate/DeviceStateManager\$FoldStateListener;" to
        (OBJECT to listOf("Landroid/hardware/devicestate/DeviceStateManager\$DeviceStateCallback;")),
    "Landroid/view/CompositionSamplingListener;" to (OBJECT to emptyList()),
    "Landroid/view/InsetsFrameProvider;" to (OBJECT to emptyList()),
    "Landroid/view/InsetsFrameProvider\$InsetsSizeOverride;" to (OBJECT to emptyList()),
)

/**
 * TranslateLauncherDatabases.shouldCopyFolder(Context): whether a launcher database is left in credential-encrypted
 * storage (/data/user/<id>/<package>), which MagicOS's copy moves to device-protected storage before restarting the app.
 */
private object ShouldCopyFolderFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("/data/user/", "databases/launcher.db"),
)

/**
 * On MagicOS the home screen is a system app that keeps its data in device-protected storage
 * (defaultToDeviceProtectedStorage), and a database it finds in credential-encrypted storage is copied over, after
 * which it restarts itself. Android honours that attribute only for system apps: an installed copy's own data is in
 * credential-encrypted storage, so it found its database "left behind" on every start and killed itself in a loop.
 * There is nothing to move where the app's storage is not device-protected.
 */
private val storagePatch = bytecodePatch(
    description = "Keeps Honor Home's data where Android puts an installed app's.",
) {
    extendWith(HONOR_HOME_EXTENSION)

    execute {
        ShouldCopyFolderFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0 }, $EXT/App;->deviceProtectedStorage(Landroid/content/Context;)Z
                    move-result v0
                    if-nez v0, :magic
                    const/4 v0, 0x0
                    return v0
                """,
                ExternalLabel("magic", implementation!!.instructions.first()),
            )
        }
    }
}

private const val LAYER_DRAW = "Landroid/graphics/drawable/LayerDrawable;->draw(Landroid/graphics/Canvas;)V"

/**
 * Honor Home turns an adaptive icon into a bitmap by drawing its background and foreground layers whole (a LayerDrawable)
 * and leaves cropping and shaping to MagicOS's theme engine (ResourcesEx.getOptimizationIcon); elsewhere every such icon
 * was a full square of the layers' bleed area. The conversion now draws the layers through Icons.drawAdaptive: the
 * middle two thirds, as Android shows an adaptive icon, in Honor's rounded-square shape.
 */
private val iconShapePatch = bytecodePatch(
    description = "Gives app icons Honor's icon shape.",
) {
    extendWith(HONOR_HOME_EXTENSION)

    execute {
        // The conversions: take the icon's background and foreground and draw a LayerDrawable of them (the home
        // screen's icon cache, and the app-recall list's icons).
        val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                val calls = m.implementation?.instructions?.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> "${r.definingClass}->${r.name}" }
                } ?: continue
                if ("Landroid/graphics/drawable/AdaptiveIconDrawable;->getBackground" in calls &&
                    "Landroid/graphics/drawable/AdaptiveIconDrawable;->getForeground" in calls &&
                    "Landroid/graphics/drawable/LayerDrawable;->draw" in calls && m.returnType == "Landroid/graphics/Bitmap;"
                ) sites += c.type to m
            }
        }
        log.info("adaptive icon conversions: ${sites.size}")
        // Measured against 14.0.0.210: 2.
        if (sites.isEmpty()) throw PatchException("no adaptive icon conversion found")
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            for (index in instructions.indices) {
                val r = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if ("${r.definingClass}->${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}" != LAYER_DRAW) continue
                val call = instructions[index] as FiveRegisterInstruction
                mutable.replaceInstruction(
                    index,
                    "invoke-static { v${call.registerC}, v${call.registerD} }, " +
                        "$EXT/Icons;->drawAdaptive(Landroid/graphics/drawable/Drawable;Landroid/graphics/Canvas;)V",
                )
            }
        }
    }
}

private const val HOME_SETTINGS = "Lcom/hihonor/android/launcher/HomeMoreSettings;"
private const val HOME_SWITCHER = "Lcom/hihonor/android/launcher/HomeSwitcher;"

/**
 * The drawer style is a home activity of its own that MagicOS's Settings app switches to; Home screen settings gets a
 * "Drawer Home" switch instead (see HomeStyle), which uses Honor Home's own HomeSwitcher.changeHomeTo.
 */
private val homeStylePatch = bytecodePatch(
    description = "Adds a drawer style switch to Home screen settings.",
) {
    extendWith(HONOR_HOME_EXTENSION)

    execute {
        // HomeSwitcher.changeHomeTo(Activity, int), called by name from HomeStyle.
        val switcher = mutableClassDefByOrNull(HOME_SWITCHER)?.methods?.firstOrNull {
            it.name == "changeHomeTo" && it.parameterTypes.map { t -> t.toString() } == listOf("Landroid/app/Activity;", "I")
        } ?: throw PatchException("HomeSwitcher.changeHomeTo(Activity, int) not found")
        if (!com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(switcher.accessFlags)) throw PatchException("changeHomeTo is not static")

        // Home screen settings' setup, right after its preferences are loaded.
        val setup = mutableClassDefByOrNull(HOME_SETTINGS)?.methods?.firstOrNull { m ->
            m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "addPreferencesFromResource"
            } == true
        } ?: throw PatchException("HomeMoreSettings no longer loads its preferences")
        val instructions = setup.implementation!!.instructions
        val loaded = instructions.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "addPreferencesFromResource"
        }
        setup.addInstructions(
            loaded + 1,
            "invoke-static { p0 }, $EXT/HomeStyle;->add(Landroid/preference/PreferenceActivity;)V",
        )

        // Making the new style's activity the preferred Home needs a system permission (see Preferred).
        val preferred = redirectCalls(
            listOf(
                "clearPackagePreferredActivities(Ljava/lang/String;)V",
                "addPreferredActivity(Landroid/content/IntentFilter;I[Landroid/content/ComponentName;Landroid/content/ComponentName;)V",
            ).associate { shape ->
                "Landroid/content/pm/PackageManager;->$shape" to
                    "$EXT/Preferred;->${shape.substringBefore('(')}(Landroid/content/pm/PackageManager;${shape.substringAfter('(')}"
            },
        )
        log.info("preferred activity calls: $preferred")
        if (preferred == 0) throw PatchException("no preferred activity calls found")

        // The style switch's own Home intent (HomeSwitcher's activity starts) goes to the new activity directly.
        val switcherClass = mutableClassDefBy(HOME_SWITCHER)
        var starts = 0
        for (m in switcherClass.methods) {
            val instructions = m.implementation?.instructions ?: continue
            for (index in instructions.indices) {
                val insn = instructions[index]
                val r = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (insn.opcode != com.android.tools.smali.dexlib2.Opcode.INVOKE_VIRTUAL || r.name != "startActivity" ||
                    r.parameterTypes.map { it.toString() } != listOf("Landroid/content/Intent;") || r.returnType != "V"
                ) continue
                val call = insn as FiveRegisterInstruction
                m.replaceInstruction(
                    index,
                    "invoke-static { v${call.registerC}, v${call.registerD} }, " +
                        "$EXT/Preferred;->startActivity(Landroid/app/Activity;Landroid/content/Intent;)V",
                )
                starts++
            }
        }
        log.info("style switch Home starts: $starts")
        if (starts == 0) throw PatchException("HomeSwitcher no longer starts the new home activity")
    }
}

/**
 * What a system home screen may do and an installed app may not: bind widgets without asking, keep its options in
 * system settings.
 */
private val systemPrivilegesPatch = bytecodePatch(
    description = "Asks for the widget permission and keeps Honor Home's options locally.",
) {
    extendWith(HONOR_HOME_EXTENSION)

    execute {
        val settings = redirectCalls(SETTINGS_CALLS)
        val widgets = redirectCalls(WIDGET_BINDS)
        log.info("settings calls: $settings, widget binds: $widgets")
        if (settings < 30) throw PatchException("only $settings settings calls found")
        if (widgets == 0) throw PatchException("no widget binds found")
    }
}

/**
 * Off MagicOS Honor Home dies at once on com.hihonor.android.os.SystemPropertiesEx (a MagicOS framework class). The
 * extension carries stand-ins for the MagicOS classes it calls (generated by .github/scripts/honor-standins, the ones
 * that need real answers written by hand: display size, users and profiles, package lookups, receivers, content
 * observers, the density); on an Honor phone the framework's own classes load first, so they never run there.
 * MagicOS's theme values become plain ones, Android's hidden parts that an installed app may not use answer "absent",
 * widget binds ask for Android's permission once, and options Android refuses to store go to the app's preferences.
 * Recents and navigation gestures stay the phone's own: Android gives them only to the system's home app.
 */
@Suppress("unused")
val enableNonMagicOsPatch = bytecodePatch(
    name = "Enable non-MagicOS ROM support",
    description = "Makes Honor Home usable as a home screen on non-Honor ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HONOR_HOME)
    dependsOn(resourcesPatch, hiddenApisPatch, systemPrivilegesPatch, storagePatch, iconShapePatch, homeStylePatch)
    extendWith(HONOR_HOME_EXTENSION)

    execute {
        val replaced = replaceMagicFrameworkIds("$EXT/Magic;", HOME_CODE_ANSWERS)
        log.info("MagicOS framework ids in code: ${replaced.size} ids, ${replaced.values.sum()} uses")
        if (replaced.size < 20) throw PatchException("only ${replaced.size} MagicOS framework ids found in code")

        val lookups = redirectMagicFrameworkLookups("$EXT/Magic;")
        log.info("Resource lookups: $lookups")

        // The home screen grids to choose from come from MagicOS's launcher_config.xml (config_desktopCellsOptionItems,
        // in /system/etc/xml); without it Honor Home fell back to 4x4 and 4x5 while its Layout dialog shows 4x6 and 5x6
        // (picking "4x6" gave 4x4). The grids of Honor's phones (the dialog's) are the fallback now.
        val grids = rewriteStringLiterals { if (it == DEFAULT_GRIDS) MAGIC_GRIDS else null }
        log.info("default home screen grids: $grids")
        // Measured against 14.0.0.210: Settings' default value and its default-index lookup.
        if (grids != 2) throw PatchException("default home screen grids: $grids places")
    }
}
