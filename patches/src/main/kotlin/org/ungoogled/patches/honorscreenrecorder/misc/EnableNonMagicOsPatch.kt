package org.ungoogled.patches.honorscreenrecorder.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.honor.MAGIC_DAY_NIGHT
import org.ungoogled.patches.honor.MAGIC_DAY_NIGHT_NO_ACTION_BAR
import org.ungoogled.patches.honor.followMagicDayNight
import org.ungoogled.patches.honor.redirectMagicFrameworkLookups
import org.ungoogled.patches.honor.replaceMagicFrameworkIds
import org.ungoogled.patches.honor.replaceMagicFrameworkReferences
import org.ungoogled.patches.honor.replaceMagicIconDrawables
import org.ungoogled.patches.honorscreenrecorder.HonorScreenRecorderConstants.COMPATIBILITY_HONOR_SCREEN_RECORDER
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.w3c.dom.Element

internal const val HONOR_SCREEN_RECORDER_EXTENSION = "extensions/honorscreenrecorder.mpe"
private const val EXT = "Lorg/ungoogled/honorscreenrecorder"
private const val PROJECTION = "$EXT/Projection;"
private const val CAPTURE = "$EXT/Capture;"
private const val WINDOWS = "$EXT/Windows;"
private const val PHONE = "$EXT/Phone;"
private const val RUNTIME = "$EXT/Magic;"
private const val SERVICE = "Lcom/hihonor/screenrecorder/ScreenRecordService;"
private const val START_ACTIVITY = "Lcom/hihonor/screenrecorder/activities/ServiceStartActivity;"
private const val SURFACE_CONTROL = "Lcom/hihonor/android/screenrecorder/activities/SurfaceControlEx;"

private const val SETTINGS_ACTIVITY_NAME = "com.hihonor.screenrecorder.activities.SettingActivity"
private const val START_ACTIVITY_NAME = "com.hihonor.screenrecorder.activities.ServiceStartActivity"
private const val SERVICE_NAME = "com.hihonor.screenrecorder.ScreenRecordService"
private const val START_ALIAS = "org.ungoogled.honorscreenrecorder.StartRecording"

private val log = java.util.logging.Logger.getLogger("HonorScreenRecorder")

/** Icons drawn by MagicOS's framework icon drawable (notification, floating control): the app's copies of their sources. */
private val ICON_SOURCES = mapOf(
    "@0x02030828" to "icsvg_public_more24",
    "@drawable/icsvg_public_recording_filled" to "icsvg_public_recording_filled",
    "@drawable/icsvg_hnscreen_recording_stop_filled" to "icsvg_hnscreen_recording_stop_filled",
)

/**
 * What MagicOS's theme answers for the recorder's references without a plain value: colours with night variants from
 * the app's own copies (its bundled Honor UI kit has the same names), Android's fonts for MagicOS's, Android's
 * day/night themes for MagicOS's.
 */
private val SCREEN_RECORDER_ANSWERS = mapOf(
    "?0x02010074" to "@color/magic_clickeffic_default_color", // attr/clickEffectColor
    "?0x020100ef" to "sans-serif", // attr/textFontFamilyRegular
    "?0x020100f0" to "sans-serif-medium", // attr/textFontFamilyMedium ("HnChinese-medium" on MagicOS)
    "?0x02010103" to "@color/magic_functional_blue", // attr/textColorPrimaryActivated
    "@0x02030828" to "@drawable/icsvg_public_more24", // drawable/icsvg_public_more24
    "@0x02060008" to "@style/$MAGIC_DAY_NIGHT", // style/Theme.Magic
    "@0x02060009" to "@style/$MAGIC_DAY_NIGHT_NO_ACTION_BAR", // style/Theme.Magic.NoActionBar
    "@0x020600cf" to "@android:style/Theme.DeviceDefault.NoActionBar.Fullscreen", // style/Theme.Magic.Dark.NoActionBar.Fullscreen
)

/**
 * The floating control's "more" button takes MagicOS's framework "more" icon, whose copies in the app its build shrank to
 * empty placeholders: drawn here (three dots, in the layer colour the button's drawable gives) into that placeholder.
 */
private const val MORE_ICON = "icsvg_public_more24"
private const val MORE_VECTOR = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="96" android:viewportHeight="96">
    <path android:fillColor="@color/ic_layer_01"
        android:pathData="M48,18m-7,0a7,7 0 1,0 14,0a7,7 0 1,0 -14,0M48,48m-7,0a7,7 0 1,0 14,0a7,7 0 1,0 -14,0M48,78m-7,0a7,7 0 1,0 14,0a7,7 0 1,0 -14,0" />
</vector>"""

private fun Element.children(tag: String) =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

private val magicResourcesPatch = resourcePatch(
    description = "Gives Honor Screen recording the sizes, fonts, colours, icons and day/night theme MagicOS would.",
) {
    execute {
        val more = get("res/drawable/$MORE_ICON.xml")
        if (!more.isFile || !more.readText().contains("<x")) throw PatchException("drawable/$MORE_ICON is not the empty placeholder expected")
        more.writeText(MORE_VECTOR)

        val icons = replaceMagicIconDrawables(ICON_SOURCES)
        log.info("MagicOS icon drawables: $icons")
        // Measured against 14.0.1.181: 3 (the notification icon, the stop button and the floating control's "more").
        if (icons < 3) throw PatchException("only $icons MagicOS icon drawables found")

        val replaced = replaceMagicFrameworkReferences(SCREEN_RECORDER_ANSWERS)
        log.info("MagicOS framework references: $replaced")
        if (replaced.size < 10) throw PatchException("only ${replaced.size} MagicOS framework references found")

        // The manifest names MagicOS's Theme.Magic for the app and Theme.Magic.NoActionBar for the settings screen by
        // id (the resources above name them, the manifest is left to this): Android's day/night ones instead. The app
        // theme is a plain Android one first, so its translucent permission screen gets the translucent day/night one.
        fun themes(application: String) = document("AndroidManifest.xml").use { manifest ->
            val app = manifest.getElementsByTagName("application").item(0) as Element
            if (app.getAttribute("android:theme") != "@0x02060008" && application == "@android:style/Theme.DeviceDefault") {
                throw PatchException("the app's theme is not MagicOS's Theme.Magic")
            }
            app.setAttribute("android:theme", application)
            val settings = app.children("activity").firstOrNull { it.getAttribute("android:name") == SETTINGS_ACTIVITY_NAME }
                ?: throw PatchException("$SETTINGS_ACTIVITY_NAME not in the manifest")
            if (settings.getAttribute("android:theme") == "@0x02060009") {
                settings.setAttribute("android:theme", "@style/$MAGIC_DAY_NIGHT_NO_ACTION_BAR")
            }
        }
        themes("@android:style/Theme.DeviceDefault")
        val (activities, styles) = followMagicDayNight()
        log.info("Day/night themes: $activities activities, $styles styles")
        // Measured: the permission screen (translucent).
        if (activities < 1) throw PatchException("themes changed: $activities activities, $styles styles")
        themes("@style/$MAGIC_DAY_NIGHT")
    }
}

/**
 * MagicOS starts recordings from its own SystemUI tile (through the recorder's start activity, guarded by a MagicOS
 * system permission) and opens the recorder's settings from Settings; off MagicOS it has no launcher icon at all. The
 * settings screen gets a launcher icon, the start activity a second one ("Start/Stop recording", the app's own string)
 * and a Quick Settings tile of its own; Android's capture dialog a screen of its own (see Projection); the recording
 * service records the screen and the microphone in the foreground, which Android 14+ wants declared.
 */
private val manifestPatch = resourcePatch(
    description = "Adds launcher icons and a Quick Settings tile for Honor Screen recording.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activities = application.children("activity")
            fun activity(name: String) = activities.firstOrNull { it.getAttribute("android:name") == name }
                ?: throw PatchException("$name not in the manifest")
            fun launcher() = manifest.createElement("intent-filter").apply {
                appendChild(manifest.createElement("action").apply { setAttribute("android:name", "android.intent.action.MAIN") })
                appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.LAUNCHER") })
            }

            // MagicOS Settings opens the settings screen through an alias of the same name, guarded by a MagicOS system
            // permission; Android resolves that name to the alias, so nothing else could open the screen.
            val aliases = application.children("activity-alias").filter { it.getAttribute("android:name") == SETTINGS_ACTIVITY_NAME }
            if (aliases.size != 1) throw PatchException("MagicOS Settings' alias of the settings screen not found")
            application.removeChild(aliases.single())
            val settings = activity(SETTINGS_ACTIVITY_NAME)
            settings.setAttribute("android:exported", "true")
            settings.removeAttribute("android:excludeFromRecents")
            settings.appendChild(launcher())

            // The start activity: only the launcher icon and the tile start it now (no MagicOS system permission).
            val start = activity(START_ACTIVITY_NAME)
            start.removeAttribute("android:permission")
            start.setAttribute("android:exported", "false")
            start.setAttribute("android:theme", "@android:style/Theme.NoDisplay")
            application.appendChild(
                manifest.createElement("activity-alias").apply {
                    setAttribute("android:name", START_ALIAS)
                    setAttribute("android:targetActivity", START_ACTIVITY_NAME)
                    setAttribute("android:exported", "true")
                    setAttribute("android:label", "@string/title_nav_1")
                    setAttribute("android:icon", "@mipmap/stat_notify_scr_rec")
                    setAttribute("android:excludeFromRecents", "true")
                    appendChild(launcher())
                },
            )
            application.appendChild(
                manifest.createElement("activity").apply {
                    setAttribute("android:name", "org.ungoogled.honorscreenrecorder.ConsentActivity")
                    setAttribute("android:exported", "false")
                    setAttribute("android:excludeFromRecents", "true")
                    setAttribute("android:taskAffinity", "org.ungoogled.honorscreenrecorder.consent")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                },
            )
            application.appendChild(
                manifest.createElement("service").apply {
                    setAttribute("android:name", "org.ungoogled.honorscreenrecorder.Tiles")
                    setAttribute("android:exported", "true")
                    setAttribute("android:label", "@string/app_name")
                    setAttribute("android:icon", "@drawable/ic_screen_notification")
                    setAttribute("android:permission", "android.permission.BIND_QUICK_SETTINGS_TILE")
                    appendChild(
                        manifest.createElement("intent-filter").apply {
                            appendChild(manifest.createElement("action").apply {
                                setAttribute("android:name", "android.service.quicksettings.action.QS_TILE")
                            })
                        },
                    )
                },
            )

            // Screen capture and microphone in the foreground (MagicOS's system copy declares only the microphone).
            val service = application.children("service").firstOrNull { it.getAttribute("android:name") == SERVICE_NAME }
                ?: throw PatchException("$SERVICE_NAME not in the manifest")
            service.setAttribute("android:foregroundServiceType", "mediaProjection|microphone")
            val manifestRoot = manifest.documentElement
            val declared = manifestRoot.children("uses-permission").map { it.getAttribute("android:name") }.toSet()
            for (permission in listOf("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION", "android.permission.FOREGROUND_SERVICE_MICROPHONE")) {
                if (permission in declared) continue
                manifestRoot.insertBefore(
                    manifest.createElement("uses-permission").apply { setAttribute("android:name", permission) },
                    application,
                )
            }
        }
    }
}

/**
 * Off MagicOS Screen recording dies at once on com.hihonor.android.os.SystemPropertiesEx (a MagicOS framework class),
 * its layouts then on MagicOS's theme attributes. The extension carries stand-ins for the 32 MagicOS classes it uses
 * (generated by .github/scripts/honor-standins); on an Honor phone the framework's own classes load first, so they never
 * run there. What only Honor's system copy may do gets Android's public way instead: the screen is recorded through a
 * MediaProjection the user consents to for each recording (Android's capture dialog) instead of a SurfaceControl display,
 * the device audio through that same projection, its windows are app overlays instead of system windows, and the call
 * state comes from the audio mode.
 */
@Suppress("unused")
val enableNonMagicOsPatch = bytecodePatch(
    name = "Enable non-MagicOS ROM support",
    description = "Makes Honor Screen recording usable on non-Honor ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HONOR_SCREEN_RECORDER)
    dependsOn(magicResourcesPatch, manifestPatch)
    extendWith(HONOR_SCREEN_RECORDER_EXTENSION)

    execute {
        fun expect(what: String, moved: Int, wanted: Int) {
            log.info("$what: $moved")
            if (moved < wanted) throw PatchException("only $moved $what found (expected $wanted)")
        }

        // The floating timer tints its recording dot with MagicOS's attribute colorHandup (by id, in Theme.Magic.Dark):
        // the app's copy of that colour. MagicOS's themes looked up by name get the app's day/night ones.
        val ids = replaceMagicFrameworkIds(RUNTIME, mapOf(0x020100ba to "color/magic_color_handup_dark"))
        log.info("MagicOS framework ids in code: $ids")
        if (ids.isEmpty()) throw PatchException("MagicOS's colorHandup not found in code")
        log.info("Resource lookups: ${redirectMagicFrameworkLookups(RUNTIME)}")

        // The screen: MagicOS's SurfaceControl display -> a MediaProjection virtual display (see Capture).
        // Measured against 14.0.1.181: 11 calls (one display, its surface, size, layer stack, two transactions, release,
        // two screenshots).
        expect(
            "screen capture calls",
            redirectCalls(
                CAPTURE,
                mapOf(
                    SURFACE_CONTROL to setOf(
                        "createDisplay(Ljava/lang/String;Z)Landroid/os/IBinder;", "openTransaction()V", "closeTransaction()V",
                        "setDisplaySurface(Landroid/os/IBinder;Landroid/view/Surface;)V",
                        "setDisplayProjection(Landroid/os/IBinder;ILandroid/graphics/Rect;Landroid/graphics/Rect;)V",
                        "setDisplayLayerStack(Landroid/os/IBinder;I)V", "destroyDisplay(Landroid/os/IBinder;)V",
                        "screenshot(Landroid/graphics/Rect;III)Landroid/graphics/Bitmap;",
                        "screenshot(Landroid/graphics/Rect;IIIIZI)Landroid/graphics/Bitmap;",
                    ),
                ),
            ),
            11,
        )

        // The capture token: MagicOS's privileged one -> the user's consent, one projection per recording (Projection).
        // Measured: the device audio's createMediaProjection / getMediaProjection / stop, two startForeground; and the
        // two notification channels (the recording one is shown minimised at importance low: see Projection).
        expect(
            "capture token calls",
            redirectCalls(
                PROJECTION,
                mapOf(
                    "Lcom/hihonor/android/media/projection/MediaProjectionManagerEx;" to setOf("createMediaProjection()Landroid/content/Intent;"),
                    "Landroid/media/projection/MediaProjectionManager;" to setOf("getMediaProjection(ILandroid/content/Intent;)Landroid/media/projection/MediaProjection;"),
                    "Landroid/media/projection/MediaProjection;" to setOf("stop()V"),
                    "Landroid/app/Service;" to setOf("startForeground(ILandroid/app/Notification;)V"),
                    "Landroid/app/NotificationManager;" to setOf("createNotificationChannel(Landroid/app/NotificationChannel;)V"),
                ),
            ),
            7,
        )

        // The call state needs READ_PHONE_STATE (Honor's system copy reads it with a privileged one): see Phone.
        // Measured: getCallState twice, listen twice (service start and end).
        expect(
            "call state calls",
            redirectCalls(
                PHONE,
                mapOf("Landroid/telephony/TelephonyManager;" to setOf("getCallState()I", "listen(Landroid/telephony/PhoneStateListener;I)V")),
            ),
            4,
        )

        // System windows -> app overlays (see Windows). Measured: 7 addView, 10 updateViewLayout, 2 setType.
        val windows = redirectCalls(
            mapOf(
                "Landroid/view/ViewManager;->addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V" to
                    "$WINDOWS->addView(Landroid/view/ViewManager;Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
                "Landroid/view/ViewManager;->updateViewLayout(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V" to
                    "$WINDOWS->updateViewLayout(Landroid/view/ViewManager;Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
                "Landroid/view/Window;->setType(I)V" to "$WINDOWS->setType(Landroid/view/Window;I)V",
            ),
        )
        expect("window calls", windows, 15)

        // "Modify system settings" is asked for only to switch Show touches, which Android keeps to itself (see Windows).
        expect(
            "system settings checks",
            redirectCalls(WINDOWS, mapOf("Landroid/provider/Settings\$System;" to setOf("canWrite(Landroid/content/Context;)Z"))),
            3,
        )

        // The storage path from Android's hidden volume list (refused here): Android's public volumes (see Storage).
        fun Method.looksUpVolumes() = parameterTypes == listOf("Z") && returnType == "Ljava/lang/String;" &&
            implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "getVolumeList" } == true
        var storage: String? = null
        classDefForEach { c -> if (storage == null && c.methods.any { it.looksUpVolumes() }) storage = c.type }
        val path = mutableClassDefBy(storage ?: throw PatchException("the recorder's storage path lookup (getVolumeList) not found"))
            .methods.first { it.looksUpVolumes() }
        path.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXT/Storage;->path(Z)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """,
        )

        val service = mutableClassDefBy(SERVICE)

        // Where a new recording begins (after the recorder's own permission checks, where it enters its first
        // recording state): without a consent for it, Android's capture dialog first (see Projection.ask).
        val begin = service.methods.firstOrNull { method ->
            method.parameterTypes.isEmpty() && method.returnType == "V" && method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                    "Last recording remained, preventing another from commencing."
            } == true
        } ?: throw PatchException("ScreenRecordService's start method not found")
        val instructions = begin.implementation!!.instructions
        // The last const-class: the first recording state, handed to the state factory right after.
        val state = instructions.indexOfLast { it.opcode == Opcode.CONST_CLASS }
        if (state < 1) throw PatchException("ScreenRecordService's start method enters no recording state")
        val factory = instructions[state - 1]
        if (factory.opcode != Opcode.IGET_OBJECT) throw PatchException("ScreenRecordService's state factory read not found")
        val next = instructions[state + 1]
        if (next.opcode != Opcode.INVOKE_VIRTUAL || ((next as ReferenceInstruction).reference as MethodReference).parameterTypes != listOf("Ljava/lang/Class;")) {
            throw PatchException("ScreenRecordService's state factory call not found")
        }
        val stateType = ((instructions[state] as ReferenceInstruction).reference as TypeReference).type
        log.info("first recording state: $stateType")
        // A no-op at the (branch target) factory read, so every path runs the check that follows it; v0 is free there
        // (the const-class writes it next).
        begin.addInstructionsAtLabel(state - 1, "nop")
        begin.addInstructionsWithLabels(
            state,
            """
                invoke-static { p0 }, $PROJECTION->ask(Landroid/app/Service;)Z
                move-result v0
                if-eqz v0, :carry
                return-void
            """,
            ExternalLabel("carry", begin.implementation!!.instructions[state]),
        )

        // Nothing of a recording outlives the service.
        val destroy = service.methods.firstOrNull { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("ScreenRecordService.onDestroy not found")
        destroy.addInstruction(0, "invoke-static {}, $PROJECTION->release()V")

        // The launcher's "Start/Stop recording" counts as MagicOS's start action (see Starter).
        val create = mutableClassDefBy(START_ACTIVITY).methods.firstOrNull {
            it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;")
        } ?: throw PatchException("ServiceStartActivity.onCreate not found")
        create.addInstruction(0, "invoke-static { p0 }, $EXT/Starter;->launch(Landroid/app/Activity;)V")
    }
}
