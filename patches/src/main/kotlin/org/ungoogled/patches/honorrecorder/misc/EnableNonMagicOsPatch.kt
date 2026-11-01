package org.ungoogled.patches.honorrecorder.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.honor.followMagicDayNight
import org.ungoogled.patches.honor.redirectMagicFrameworkLookups
import org.ungoogled.patches.honor.replaceMagicFrameworkIds
import org.ungoogled.patches.honor.replaceMagicFrameworkReferences
import org.ungoogled.patches.honor.replaceMagicIconDrawables
import org.ungoogled.patches.honor.replaceMagicInterpolators
import org.ungoogled.patches.honorrecorder.HonorRecorderConstants.COMPATIBILITY_HONOR_RECORDER
import org.ungoogled.patches.samsung.redirectCalls
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val HONOR_RECORDER_EXTENSION = "extensions/honorrecorder.mpe"
private const val RUNTIME = "Lorg/ungoogled/honorrecorder/Magic;"
private const val PHONE = "Lorg/ungoogled/honorrecorder/Phone;"
private const val RECORD_SERVICE = "Lcom/android/soundrecorder/RecordService;"
private const val PERMISSION_UTILS = "Lcom/hihonor/soundrecorder/util/PermissionUtils;"
private const val RECORD_LIST_FRAGMENT = "Lcom/android/soundrecorder/ui/RecordListFragment;"
private const val RECORD_LIST_BASE_FRAGMENT = "Lcom/android/soundrecorder/ui/RecordListBaseFragment;"
private const val EXT = "Lorg/ungoogled/honorrecorder"
private const val RECORDER_UTILS = "Lcom/android/soundrecorder/util/RecorderUtils;"

private val log = java.util.logging.Logger.getLogger("HonorRecorder")

/** Notification, capsule and tag icons drawn by MagicOS's framework icon drawable: the app's copies of their sources. */
private val RECORDER_ICON_SOURCES = mapOf(
    "@0x02030383" to "icsvg_hnrecorder_stop_filled",
    "@0x0203079a" to "icsvg_public_cancel",
    "@0x02030853" to "icsvg_public_pause_filled",
    "@0x02030867" to "icsvg_public_play_filled",
    "@0x020312f5" to "icsvg_public_cancel_bold",
    "@0x020313ad" to "icsvg_public_recorder_bold",
    "@0x020313d2" to "icsvg_public_tag_bold",
)

/**
 * Two icons MagicOS takes from its framework have no copy in the app (its build shrank the Honor UI kit's copies to
 * empty placeholders): drawn here, simply, into those placeholders. Drawable name to vector.
 */
private val DRAWN_ICONS = mapOf(
    // the recording notification's stop button (a rounded square, layer colour from the notification's drawable)
    "icsvg_hnrecorder_stop_filled" to """<vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="24dp" android:height="24dp" android:viewportWidth="96" android:viewportHeight="96">
        <path android:fillColor="@color/ic_layer_01"
            android:pathData="M30,24h36a6,6 0 0 1 6,6v36a6,6 0 0 1 -6,6h-36a6,6 0 0 1 -6,-6v-36a6,6 0 0 1 6,-6z" />
    </vector>""",
    // the playback screen's "done" action (a check mark in the toolbar's icon colour)
    "icsvg_public_confirm" to """<vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="24dp" android:height="24dp" android:viewportWidth="96" android:viewportHeight="96">
        <path android:strokeColor="?android:attr/colorControlNormal" android:strokeWidth="7"
            android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="M20,50 L40,70 L78,28" />
    </vector>""",
)

/**
 * What MagicOS's theme answers for Recorder's references without a plain value: colours with night variants, from the
 * app's own copies (its bundled Honor UI kit has the same names and night variants), Android's medium font for
 * MagicOS's own, Android's day/night theme and search view style for MagicOS's.
 */
private val RECORDER_ANSWERS = mapOf(
    "?0x02010074" to "@color/magic_clickeffic_default_color", // attr/clickEffectColor
    "?0x020100bd" to "@color/magic_color_2", // attr/magicColor2
    "?0x020100bf" to "@color/magic_color_4", // attr/magicColor4
    "?0x020100c3" to "@color/magic_color_8", // attr/magicColor8
    "?0x020100c6" to "@color/magic_color_11", // attr/magicColor11
    "?0x020100ef" to "sans-serif", // attr/textFontFamilyRegular
    "?0x020100f0" to "sans-serif-medium", // attr/textFontFamilyMedium ("HnChinese-medium" on MagicOS)
    "?0x020100fd" to "@color/magic_color_tertiary", // attr/colorTertiary (MagicOS: a selector of it)
    "?0x02010101" to "@dimen/magic_tips_bg_alpha", // attr/tipsBackgroundAlpha
    "?0x02010103" to "@color/magic_functional_blue", // attr/textColorPrimaryActivated
    "?0x02010104" to "@color/magic_functional_blue", // attr/textColorSecondaryActivated
    "@0x02030028" to "@drawable/icsvg_public_confirm", // drawable/ic_public_ok (a menu icon)
    "@0x02060009" to "@style/MagicDayNight.NoActionBar", // style/Theme.Magic.NoActionBar (parent of the app's themes)
    "@0x020600e1" to "@style/Widget.Magic.HwEditText.Linear", // style/Widget.Magic.EditText.Linear
    "@0x020601a6" to "@android:style/Widget.Material.SearchView", // style/Widget.Magic.SearchView.PatternSpinnerOut
    "@0x021000d9" to "@id/magic_card_effect_view_id", // id/magic_card_effect_view_id
)

/** MagicOS framework resources Recorder's code names by id, and what it gets instead (the app's own copies). */
private val RECORDER_CODE_ANSWERS = mapOf(
    0x020100c7 to "dimen/magic_dimens_default_start", // attr/defaultPaddingStart
    0x020100c8 to "dimen/magic_dimens_max_start", // attr/maxPaddingStart
    0x020100c9 to "dimen/magic_dimens_default_end", // attr/defaultPaddingEnd
    0x020100ca to "dimen/magic_dimens_max_end", // attr/maxPaddingEnd
    0x020100e8 to "dimen/magic_text_size_body1", // attr/textSizeBody1
    0x02010103 to "color/magic_functional_blue", // attr/textColorPrimaryActivated
    0x02030026 to "drawable/icsvg_public_back", // drawable/ic_public_back (toolbar navigation)
    0x02030027 to "drawable/icsvg_public_cancel", // drawable/ic_public_cancel (toolbar navigation)
    0x021000d9 to "id/magic_card_effect_view_id",
)

private val magicResourcesPatch = resourcePatch(
    description = "Gives Honor Recorder the sizes, fonts, colours, icons and day/night theme MagicOS would.",
) {
    execute {
        for ((name, vector) in DRAWN_ICONS) {
            val file = get("res/drawable/$name.xml")
            if (!file.isFile || !file.readText().contains("<x")) throw PatchException("drawable/$name is not the empty placeholder expected")
            file.writeText(vector)
        }

        val icons = replaceMagicIconDrawables(RECORDER_ICON_SOURCES)
        log.info("MagicOS icon drawables: $icons")
        if (icons < 10) throw PatchException("only $icons MagicOS icon drawables found")

        val interpolators = replaceMagicInterpolators()
        log.info("MagicOS interpolators: $interpolators")

        val replaced = replaceMagicFrameworkReferences(RECORDER_ANSWERS)
        log.info("MagicOS framework references: $replaced")
        if (replaced.size < 30) throw PatchException("only ${replaced.size} MagicOS framework references found")

        val (activities, styles) = followMagicDayNight()
        log.info("Day/night themes: $activities activities, $styles styles")
    }
}

/**
 * Off MagicOS Recorder dies at once on com.hihonor.android.app.GtaPkgNameConstant (a MagicOS framework class), its
 * layouts then on MagicOS's theme attributes, its themes on MagicOS's own and its code on MagicOS's framework
 * resources. The extension carries stand-ins for the 33 MagicOS classes it uses (generated by
 * .github/scripts/honor-standins; ActionBarEx and HwSearchAnimationUtils hand-written, as their members name the
 * toolbar class the app bundles); on an Honor phone the framework's own classes load first, so they never run there.
 * What only Honor's system copy may do (read the call state, silence the ringer, hold the notification permission)
 * gets a plain Android answer, and MagicOS's collapsing title bar the list's own toolbar. Call recording needs
 * privileged permissions and is not offered.
 */
@Suppress("unused")
val enableNonMagicOsPatch = bytecodePatch(
    name = "Enable non-MagicOS ROM support",
    description = "Makes Honor Recorder usable on non-Honor ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HONOR_RECORDER)
    dependsOn(magicResourcesPatch)
    extendWith(HONOR_RECORDER_EXTENSION)

    execute {
        val replaced = replaceMagicFrameworkIds(RUNTIME, RECORDER_CODE_ANSWERS)
        log.info("MagicOS framework ids in code: ${replaced.map { "0x%08x=%d".format(it.key, it.value) }}")
        if (replaced.size < RECORDER_CODE_ANSWERS.size) throw PatchException("only ${replaced.size} MagicOS framework ids found in code")

        val lookups = redirectMagicFrameworkLookups(RUNTIME)
        log.info("Resource lookups: $lookups")

        // The call state needs READ_PHONE_STATE (Honor's system copy reads it with a privileged one): see Phone.
        val phone = redirectCalls(
            PHONE,
            mapOf("Landroid/telephony/TelephonyManager;" to setOf("getCallState()I", "listen(Landroid/telephony/PhoneStateListener;I)V")),
        )
        log.info("Call state calls: $phone")
        // Measured against 14.0.1.197: getCallState 3 times, listen twice (start and stop).
        if (phone < 5) throw PatchException("only $phone call state calls found")

        // Toolbar navigation icons from the Honor UI kit get the theme's colour (see Magic.setNavigationIcon).
        val navigation = redirectCalls(RUNTIME, mapOf("Landroid/widget/Toolbar;" to setOf("setNavigationIcon(I)V")))
        log.info("Navigation icons: $navigation")
        // Measured against 14.0.1.197: 6 (back / cancel in the call list, the playback screen, the selection mode).
        if (navigation < 3) throw PatchException("only $navigation navigation icon calls found")

        // Silencing the ringer while recording needs Do Not Disturb access: see Phone.
        val ringer = redirectCalls(PHONE, mapOf("Landroid/media/AudioManager;" to setOf("setRingerMode(I)V")))
        val service = mutableClassDefBy(RECORD_SERVICE)
        val mute = service.methods.firstOrNull { it.name == "setMuteRingerMode" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("RecordService.setMuteRingerMode not found")
        val instructions = mute.implementation!!.instructions
        val read = instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_VIRTUAL && ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                r.definingClass == "Landroid/media/AudioManager;" && r.name == "getRingerMode"
            }
        }
        if (read < 0) throw PatchException("RecordService.setMuteRingerMode reads no ringer mode")
        mute.replaceInstruction(read, "invoke-static { v${(instructions[read] as FiveRegisterInstruction).registerC} }, $PHONE->ringerModeBeforeMute(Landroid/media/AudioManager;)I")
        log.info("Ringer mode calls: $ringer")
        if (ringer < 3) throw PatchException("only $ringer ringer mode calls found")

        // The notification permission Recorder never asks for: see Phone.askForNotifications.
        val permissions = mutableClassDefBy(PERMISSION_UTILS).methods.firstOrNull {
            it.name == "requestAllPermission" && it.parameterTypes == listOf("Landroid/app/Activity;")
        } ?: throw PatchException("PermissionUtils.requestAllPermission(Activity) not found")
        permissions.addInstruction(0, "invoke-static { p0 }, $PHONE->askForNotifications(Landroid/app/Activity;)V")

        // The recordings list's collapsing title bar is MagicOS's framework widget (HwExpandedAppbarController, with
        // framework layouts): elsewhere the list takes the plain toolbar layout the call recordings list uses.
        val list = mutableClassDefBy(RECORD_LIST_FRAGMENT).methods.firstOrNull {
            it.name == "isAppbarCollapsePermitted" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        } ?: throw PatchException("RecordListFragment.isAppbarCollapsePermitted not found")
        list.addInstructions(0, "invoke-static {}, $RUNTIME->onMagicOs()Z\nmove-result v0\nreturn v0")
        // ... and that toolbar becomes the action bar (see Appbar), which MagicOS's title bar did.
        if (mutableClassDefBy(RECORD_LIST_BASE_FRAGMENT).fields.none { it.name == "mHwToolbar" }) {
            throw PatchException("RecordListBaseFragment.mHwToolbar not found")
        }
        val init = mutableClassDefBy(RECORD_LIST_FRAGMENT).methods.firstOrNull { it.name == "initActionBar" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("RecordListFragment.initActionBar not found")
        val bar = init.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_VIRTUAL && ((it as ReferenceInstruction).reference as MethodReference).name == "getActionBar"
        }
        if (bar < 0) throw PatchException("RecordListFragment.initActionBar reads no action bar")
        val activity = (init.implementation!!.instructions[bar] as FiveRegisterInstruction).registerC
        init.replaceInstruction(bar, "invoke-static { v$activity, p0 }, $EXT/Appbar;->actionBar(Landroid/app/Activity;Ljava/lang/Object;)Landroid/app/ActionBar;")
        // ... and its see-through status bar stays see-through (see Appbar.setTranslucentStatus).
        val translucent = mutableClassDefBy(RECORDER_UTILS).methods.firstOrNull {
            it.name == "setTranslucentStatus" && it.parameterTypes == listOf("Landroid/app/Activity;", "Z")
        } ?: throw PatchException("RecorderUtils.setTranslucentStatus not found")
        translucent.addInstructions(0, "invoke-static { p0, p1 }, $EXT/Appbar;->setTranslucentStatus(Landroid/app/Activity;Z)V\nreturn-void")
    }
}
