package org.ungoogled.patches.coloroslauncher.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.coloros.exportDynamicReceivers
import org.ungoogled.patches.coloroslauncher.ColorOsLauncherConstants.COMPATIBILITY_COLOROS_LAUNCHER
import org.ungoogled.patches.coloroslauncher.HIDDEN_CONSTRUCTORS
import org.ungoogled.patches.coloroslauncher.HIDDEN_CONSTRUCTORS_13
import org.ungoogled.patches.coloroslauncher.HIDDEN_CONSTRUCTORS_15
import org.ungoogled.patches.coloroslauncher.HIDDEN_CONSTRUCTORS_16_17
import org.ungoogled.patches.coloroslauncher.HIDDEN_CONSTRUCTORS_CANARY
import org.ungoogled.patches.coloroslauncher.HIDDEN_MEMBERS
import org.ungoogled.patches.coloroslauncher.HIDDEN_MEMBERS_13
import org.ungoogled.patches.coloroslauncher.HIDDEN_MEMBERS_15
import org.ungoogled.patches.coloroslauncher.HIDDEN_MEMBERS_16_17
import org.ungoogled.patches.coloroslauncher.HIDDEN_MEMBERS_CANARY
import org.ungoogled.patches.coloroslauncher.HIDDEN_SUPERCLASSES
import org.ungoogled.patches.coloroslauncher.HIDDEN_SUPERCLASSES_15
import org.ungoogled.patches.coloroslauncher.INACCESSIBLE_INTERFACES
import org.ungoogled.patches.coloroslauncher.INACCESSIBLE_INTERFACES_15
import org.ungoogled.patches.coloroslauncher.SUPER_CONSTRUCTORS
import org.ungoogled.patches.coloroslauncher.SUPER_CONSTRUCTORS_15
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.shared.addDefaultsForOlderAndroid
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.ungoogled.patches.shared.enablePageSizeCompat
import org.ungoogled.patches.shared.redirectNewerJavaCalls
import org.ungoogled.patches.shared.supportedVersionCheck
import org.ungoogled.patches.coloros.privateFrameworkClassesPatch

internal const val COLOROS_LAUNCHER_EXTENSION = "extensions/coloroslauncher.mpe"

private const val EXT = "Lorg/ungoogled/coloroslauncher"
private const val RUNTIME = "$EXT/ColorOS;"

private val log = java.util.logging.Logger.getLogger("OnePlusSystemLauncher")

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
    // The per-user calls (hidden or system-only where not public): for the app's own user.
    "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I" to "GetIntForUser",
    "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)I" to "GetIntForUser",
    "getLongForUser(Landroid/content/ContentResolver;Ljava/lang/String;JI)J" to "GetLongForUser",
    "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;" to "GetStringForUser",
    "putIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)Z" to "PutIntForUser",
    "putStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;I)Z" to "PutStringForUser",
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
}

/** Window contexts and windows of types only the system may use (see Windows). */
private val WINDOW_CALLS = listOf(
    "Landroid/content/Context;" to "createWindowContext(ILandroid/os/Bundle;)Landroid/content/Context;",
    "Landroid/content/Context;" to "createWindowContext(Landroid/view/Display;ILandroid/os/Bundle;)Landroid/content/Context;",
    "Landroid/view/ViewManager;" to "addView(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
    "Landroid/view/ViewManager;" to "updateViewLayout(Landroid/view/View;Landroid/view/ViewGroup\$LayoutParams;)V",
    "Landroid/view/ViewManager;" to "removeView(Landroid/view/View;)V",
    "Landroid/view/WindowManager;" to "removeViewImmediate(Landroid/view/View;)V",
    "Landroid/content/Context;" to "getDisplay()Landroid/view/Display;",
    "Landroid/window/OnBackInvokedDispatcher;" to "registerOnBackInvokedCallback(ILandroid/window/OnBackInvokedCallback;)V",
    "Landroid/content/ContextWrapper;" to "getDisplay()Landroid/view/Display;",
).associate { (owner, shape) ->
    "$owner->$shape" to "$EXT/Windows;->${shape.substringBefore('(')}($owner${shape.substringAfter('(')}"
}

/** Reflection on Android's hidden parts (see Reflect). */
private val REFLECTION_CALLS = listOf(
    "getField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
    "getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;",
    "getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
    "getDeclaredMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
).associate { shape ->
    "Ljava/lang/Class;->$shape" to "$EXT/Reflect;->${shape.substringBefore('(')}(Ljava/lang/Class;${shape.substringAfter('(')}"
}

/** Calls an installed app may make but that fail for it (see Calls). */
private val GUARDED_CALLS = mapOf(
    "Landroid/content/ContentResolver;->registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;)V" to
        "$EXT/Calls;->registerContentObserver(Landroid/content/ContentResolver;Landroid/net/Uri;ZLandroid/database/ContentObserver;)V",
    "Landroid/app/ActivityManager;->getCurrentUser()I" to "$EXT/Calls;->getCurrentUser()I",
    // A blur of radius 0 (the long-pressed icon's resize frame starts at 0): Android 12 and 13 throw "nativePtr is null"
    // while drawing, and the icon menu and the dragged icon were not drawn at all.
    "Landroid/graphics/RenderEffect;->createBlurEffect(FFLandroid/graphics/Shader\$TileMode;)Landroid/graphics/RenderEffect;" to
        "$EXT/Blurs;->createBlurEffect(FFLandroid/graphics/Shader\$TileMode;)Landroid/graphics/RenderEffect;",
    "Landroid/graphics/RenderEffect;->createBlurEffect(FFLandroid/graphics/RenderEffect;Landroid/graphics/Shader\$TileMode;)Landroid/graphics/RenderEffect;" to
        "$EXT/Blurs;->createBlurEffect(FFLandroid/graphics/RenderEffect;Landroid/graphics/Shader\$TileMode;)Landroid/graphics/RenderEffect;",
) + listOf(
    "getBoolean(Ljava/lang/String;Ljava/lang/String;Z)Z",
    "getFloat(Ljava/lang/String;Ljava/lang/String;F)F",
    "getString(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
    "addOnPropertiesChangedListener(Ljava/lang/String;Ljava/util/concurrent/Executor;" +
        "Landroid/provider/DeviceConfig\$OnPropertiesChangedListener;)V",
).associate { shape ->
    // The listener's type is a system API the extension cannot name: Object in the helper.
    "Landroid/provider/DeviceConfig;->$shape" to
        "$EXT/Calls;->" + shape.replace("Landroid/provider/DeviceConfig\$OnPropertiesChangedListener;", "Ljava/lang/Object;")
}

/**
 * The manifest's shared user id (oppo.uid.launcher) belongs to OPPO's signature: an installed copy goes without. OPPO's
 * image libraries are 4 KB aligned: page size compatibility mode on 16 KB phones (see enablePageSizeCompat).
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            if (root.getAttribute("android:sharedUserId").isEmpty()) throw PatchException("no shared user id in the manifest")
            root.removeAttribute("android:sharedUserId")
            root.removeAttribute("android:sharedUserLabel")
        }
        enablePageSizeCompat()
    }
}

/**
 * 15.6.24 declares Android 11 as its minimum but has some resources (colors, the home settings' styles, ...) only for
 * Android 12 and newer: they get defaults (see addDefaultsForOlderAndroid). 16.4.30 needs Android 13 anyway.
 */
private val olderAndroidResourcesPatch = resourcePatch {
    execute {
        if (packageMetadata.versionName != "15.6.24") return@execute
        val added = addDefaultsForOlderAndroid()
        log.info("resources given defaults for Android 11: $added")
        if (added < 100) throw PatchException("only $added resources given defaults for Android 11")
    }
}

/**
 * UserManager.getUserInfo needs QUERY_USERS for any user: 16.4.30 asks it about each profile at start (looking for a work
 * profile) and crashed in a loop on phones with a work profile or private space. It goes through Members, which answers
 * a profile of the app's own user from public calls (see Fallbacks.profileInfo). PackageManager's lookups in another
 * user need INTERACT_ACROSS_USERS: with a work profile the loader and the badge loader died at every start (grey home
 * screen); Members answers them for the app's own profiles from LauncherApps (see Profiles).
 */
private val REFUSED_USER_CALLS = setOf(
    "Landroid/os/UserManager;->getUserInfo(I)Landroid/content/pm/UserInfo;",
    "Landroid/content/pm/PackageManager;->queryIntentActivitiesAsUser(Landroid/content/Intent;II)Ljava/util/List;",
    "Landroid/content/pm/PackageManager;->queryIntentActivitiesAsUser(Landroid/content/Intent;ILandroid/os/UserHandle;)Ljava/util/List;",
    "Landroid/content/pm/PackageManager;->getPackageUidAsUser(Ljava/lang/String;I)I",
    "Landroid/content/pm/PackageManager;->getPackageUidAsUser(Ljava/lang/String;II)I",
    "Landroid/content/pm/PackageManager;->getApplicationInfoAsUser(Ljava/lang/String;II)Landroid/content/pm/ApplicationInfo;",
    "Landroid/content/pm/PackageManager;->getApplicationInfoAsUser(Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/pm/ApplicationInfo;",
)

/**
 * The night page backgrounds. ColorOS's own controls (COUI) are designed for ColorOS's force-dark: at night the window
 * and list backgrounds stay white (coui_color_background_with_card's night value is white, coui_color_background has
 * none) and the system darkens them while drawing. Android's force dark does the same on Pixels, but One UI does not,
 * so on a Galaxy in dark mode the launcher's settings were white text on white. They get COUI's own dark values.
 */
private val NIGHT_BACKGROUNDS = mapOf(
    "coui_color_background" to "#ff000000",
    "coui_color_background_with_card" to "#ff000000",
)

private val nightBackgroundsPatch = resourcePatch {
    execute {
        val path = "res/values-night/colors.xml"
        decode(path)
        if (!get(path).isFile) throw PatchException("no $path")
        val set = mutableSetOf<String>()
        document(path).use { xml ->
            val root = xml.documentElement
            val colors = xml.getElementsByTagName("color")
            for (i in 0 until colors.length) {
                val color = colors.item(i) as org.w3c.dom.Element
                val value = NIGHT_BACKGROUNDS[color.getAttribute("name")] ?: continue
                color.textContent = value
                set += color.getAttribute("name")
            }
            for ((name, value) in NIGHT_BACKGROUNDS) {
                if (name in set) continue
                root.appendChild(xml.createElement("color").apply { setAttribute("name", name); textContent = value })
                set += name
            }
        }
        log.info("night backgrounds: $set")
    }
}

/**
 * Large app icons (resizing an icon on the home screen) are drawn by ColorOS's icon library with the phone's icon style,
 * which only ColorOS sets (an OPPO configuration value): elsewhere the style is missing, every large icon failed ("Failed
 * to load the icon resource. Try again later.") and the icon went back to its size. The large-icon code now gets the
 * library's default style (see Icons). Normal icons do not read it and keep their look.
 */
private val morphIconStylePatch = bytecodePatch {
    execute {
        // 15.6.24 has no resizable icons.
        if (packageMetadata.versionName == "15.6.24") return@execute
        val getConfig = "Lcom/android/launcher/theme/LauncherIconConfig;->getIconConfig()Lcom/oplus/uxicon/helper/IconConfig;"
        val utils = mutableClassDefBy("Lcom/android/common/util/IconUtils;")
        var hooked = 0
        for (method in utils.methods) {
            val instructions = method.implementation?.instructions ?: continue
            val sites = instructions.withIndex().filter { (_, insn) ->
                insn.opcode == Opcode.INVOKE_VIRTUAL && ((insn as ReferenceInstruction).reference as? MethodReference)?.let {
                    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == getConfig
                } == true
            }.map { it.index }
            for (index in sites.reversed()) {
                val move = instructions[index + 1]
                if (move.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("IconUtils.${method.name}: getIconConfig result not moved")
                val r = (move as OneRegisterInstruction).registerA
                method.addInstructions(
                    index + 2,
                    """
                        invoke-static/range { v$r .. v$r }, $EXT/Icons;->style(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v$r
                        check-cast v$r, Lcom/oplus/uxicon/helper/IconConfig;
                    """,
                )
                hooked++
            }
        }
        log.info("large icon style reads: $hooked")
        // Measured against 16.4.30: 3 (morph icon, its fallback, app clusters).
        if (hooked == 0) throw PatchException("no icon style reads in IconUtils")
    }
}

/**
 * "Double-tap to lock": the launcher turns the screen off with PowerManager.goToSleep, which needs a system permission,
 * so off ColorOS the double tap did nothing. It locks through an accessibility service the patch adds (extension
 * LockService, Android's GLOBAL_ACTION_LOCK_SCREEN); turning the option on asks for it (see Lock).
 */
private val lockServiceResourcesPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            application.appendChild(manifest.createElement("service").apply {
                setAttribute("android:name", "org.ungoogled.coloroslauncher.LockService")
                setAttribute("android:exported", "true")
                setAttribute("android:label", "@string/ungoogled_lock_service_label")
                setAttribute("android:permission", "android.permission.BIND_ACCESSIBILITY_SERVICE")
                appendChild(manifest.createElement("intent-filter").apply {
                    appendChild(manifest.createElement("action").apply {
                        setAttribute("android:name", "android.accessibilityservice.AccessibilityService")
                    })
                })
                appendChild(manifest.createElement("meta-data").apply {
                    setAttribute("android:name", "android.accessibilityservice")
                    setAttribute("android:resource", "@xml/ungoogled_lock_service")
                })
            })
        }
        document("res/values/strings.xml").use { strings ->
            strings.documentElement.appendChild(strings.createElement("string").apply {
                setAttribute("name", "ungoogled_lock_service_label")
                textContent = "System Launcher: double-tap to lock"
            })
            strings.documentElement.appendChild(strings.createElement("string").apply {
                setAttribute("name", "ungoogled_lock_service_description")
                textContent = "Locks the screen when you double-tap an empty space on the home screen. It reads no screen content."
            })
        }
        get("res/xml/ungoogled_lock_service.xml").apply {
            parentFile.mkdirs()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
                    android:description="@string/ungoogled_lock_service_description"
                    android:accessibilityFeedbackType="feedbackGeneric"
                    android:accessibilityFlags="flagDefault"
                    android:canRetrieveWindowContent="false"
                    android:notificationTimeout="0" />
                """.trimIndent(),
            )
        }
    }
}

private val lockScreenPatch = bytecodePatch {
    dependsOn(lockServiceResourcesPatch)
    execute {
        val sleeps = redirectCalls(
            mapOf(
                "Landroid/os/PowerManager;->goToSleep(J)V" to "$EXT/Lock;->goToSleep(Landroid/os/PowerManager;J)V",
            ),
        )
        log.info("screen lock calls: $sleeps")
        // Measured against 16.4.30 and 15.6.24: 2 (empty home screen, widget area).
        if (sleeps == 0) throw PatchException("no PowerManager.goToSleep calls found")
        val change = mutableClassDefBy("Lcom/android/launcher/settings/LauncherSettingsFragment;").methods.firstOrNull {
            it.name == "onPreferenceChange" && it.returnType == "Z" && it.parameterTypes.size == 2
        } ?: throw PatchException("LauncherSettingsFragment.onPreferenceChange not found")
        change.addInstructions(
            0,
            "invoke-static/range { p1 .. p2 }, $EXT/Lock;->preferenceChanged(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
    }
}

/**
 * Home screen pages redraw what changed. ColorOS's workspace replaces Android's (hidden) ViewGroup.dispatchGetDisplayList
 * with its own loop that rebuilds each page's drawing through hidden View members (mPrivateFlags,
 * updateDisplayListIfDirty); an installed app may not use them, so a page that changed while the workspace itself did not
 * was never redrawn: while dragging an app the drop position stayed where the drag began, and in edit mode an app
 * dropped (back in its place or elsewhere) stayed invisible until edit mode ended. The workspace now uses Android's own
 * rebuild.
 */
private val workspaceRedrawPatch = bytecodePatch {
    execute {
        val workspace = mutableClassDefBy("Lcom/android/launcher3/OplusWorkspace;")
        val method = workspace.methods.firstOrNull {
            it.name == "dispatchGetDisplayList" && it.returnType == "V" && it.parameterTypes.isEmpty()
        } ?: throw PatchException("OplusWorkspace.dispatchGetDisplayList not found")
        method.addInstructions(
            0,
            """
                invoke-super { p0 }, Landroid/view/ViewGroup;->dispatchGetDisplayList()V
                return-void
            """,
        )
    }
}

/**
 * The background behind the app drawer, folders and an app's menu (see Backgrounds): a row in Home screen settings
 * (Effects; 15.6.24: the first section) chooses the grey stand-in for ColorOS's blurred wallpaper, a real blur (Android
 * 12+ window blur), transparent or a colour with a transparency.
 */
private val backgroundSettingsResourcesPatch = resourcePatch {
    execute {
        document("res/values/strings.xml").use { strings ->
            strings.documentElement.appendChild(strings.createElement("string").apply {
                setAttribute("name", "ungoogled_menu_background_title")
                textContent = "Background behind the drawer and menus"
            })
        }
        val path = "res/xml/launcher_settings.xml"
        if (!get(path).isFile) throw PatchException("no $path")
        document(path).use { xml ->
            val categories = xml.getElementsByTagName("com.coui.appcompat.preference.COUIPreferenceCategory")
            val all = (0 until categories.length).map { categories.item(it) as org.w3c.dom.Element }
            val category = all.firstOrNull { it.getAttribute("android:key") == "category_launcher_vfx" }
                ?: all.firstOrNull { it.getAttribute("android:key") == "launcher_category" }
                ?: throw PatchException("no Effects section in $path")
            category.appendChild(xml.createElement("com.coui.appcompat.preference.COUIJumpPreference").apply {
                setAttribute("android:key", "ungoogled_menu_background")
                setAttribute("android:title", "@string/ungoogled_menu_background_title")
                setAttribute("android:persistent", "false")
            })
        }
    }
}

private val backgroundsPatch = bytecodePatch {
    dependsOn(backgroundSettingsResourcesPatch)
    execute {
        val ext = "$EXT/Backgrounds;"
        // The drawer/folder blur layer.
        val staticView = mutableClassDefBy("Lcom/android/launcher/views/OplusStaticBlurView;")
        var backgrounds = 0
        for (method in staticView.methods) {
            val instructions = method.implementation?.instructions ?: continue
            instructions.withIndex().filter { (_, insn) ->
                insn.opcode == Opcode.INVOKE_VIRTUAL && ((insn as ReferenceInstruction).reference as? MethodReference)?.let {
                    it.name == "setBackground" && it.parameterTypes.map { t -> t.toString() } == listOf("Landroid/graphics/drawable/Drawable;")
                } == true
            }.map { it.index }.reversed().forEach { index ->
                val call = instructions[index] as FiveRegisterInstruction
                method.replaceInstruction(
                    index,
                    "invoke-static { v${call.registerC}, v${call.registerD} }, $ext->setStaticBackground(Landroid/view/View;Landroid/graphics/drawable/Drawable;)V",
                )
                backgrounds++
            }
        }
        // Measured against 16.4.30 and 15.6.24: 2 (the default colour, the blurred wallpaper).
        if (backgrounds == 0) throw PatchException("OplusStaticBlurView sets no background")
        val alpha = staticView.methods.firstOrNull { it.name == "setAlpha" && it.parameterTypes.map { t -> t.toString() } == listOf("F") }
            ?: throw PatchException("OplusStaticBlurView.setAlpha not found")
        alpha.addInstructions(0, "invoke-static { p0, p1 }, $ext->staticAlpha(Landroid/view/View;F)V")

        // 15.6.24's drawer also draws a dark tint over the blur (OplusAllAppsBg; 16.4.30 has none).
        mutableClassDefByOrNull("Lcom/android/launcher3/allapps/search/OplusAllAppsBg;")?.methods?.forEach { method ->
            val instructions = method.implementation?.instructions ?: return@forEach
            instructions.withIndex().filter { (_, insn) ->
                insn.opcode == Opcode.INVOKE_VIRTUAL && ((insn as ReferenceInstruction).reference as? MethodReference)?.let {
                    it.name == "setBackground" && it.parameterTypes.map { t -> t.toString() } == listOf("Landroid/graphics/drawable/Drawable;")
                } == true
            }.map { it.index }.reversed().forEach { index ->
                val call = instructions[index] as FiveRegisterInstruction
                method.replaceInstruction(
                    index,
                    "invoke-static { v${call.registerC}, v${call.registerD} }, $ext->setDrawerTint(Landroid/view/View;Landroid/graphics/drawable/Drawable;)V",
                )
            }
        }

        // An app's menu.
        val popup = mutableClassDefBy("Lcom/android/launcher3/popup/PopupBlurView;")
        val wallpaper = popup.methods.firstOrNull { it.name == "setWallpaperDrawable" && it.parameterTypes.size == 1 }
            ?: throw PatchException("PopupBlurView.setWallpaperDrawable not found")
        wallpaper.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $ext->popupWallpaper(Landroid/view/View;Landroid/graphics/drawable/Drawable;)Landroid/graphics/drawable/Drawable;
                move-result-object p1
            """,
        )
        val dragLayer = popup.methods.firstOrNull { it.name == "setDragLayerDrawable" && it.parameterTypes.size == 1 }
            ?: throw PatchException("PopupBlurView.setDragLayerDrawable not found")
        dragLayer.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $ext->popupDragLayer(Landroid/view/View;Landroid/graphics/drawable/Drawable;)Landroid/graphics/drawable/Drawable;
                move-result-object p1
            """,
        )
        val anim = popup.methods.firstOrNull {
            it.name == "createBlurAnim" && it.returnType == "Landroid/animation/ObjectAnimator;" && it.parameterTypes.map { t -> t.toString() } == listOf("Z")
        } ?: throw PatchException("PopupBlurView.createBlurAnim not found")
        val animReturns = anim.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        if (animReturns.isEmpty()) throw PatchException("PopupBlurView.createBlurAnim has no return")
        for ((index, insn) in animReturns.reversed()) {
            val register = (insn as OneRegisterInstruction).registerA
            anim.addInstructions(index, "invoke-static/range { v$register .. v$register }, $ext->popupAnimator(Landroid/animation/ObjectAnimator;)V")
        }
        val finish = popup.methods.firstOrNull { it.name == "finish" && it.returnType == "V" }
            ?: throw PatchException("PopupBlurView.finish not found")
        finish.addInstructions(0, "invoke-static { p0 }, $ext->popupGone(Landroid/view/View;)V")

        // The settings row.
        val settings = mutableClassDefBy("Lcom/android/launcher/settings/LauncherSettingsFragment;").methods.firstOrNull {
            it.name == "onCreatePreferences" && it.returnType == "V" && it.parameterTypes.size == 2
        } ?: throw PatchException("LauncherSettingsFragment.onCreatePreferences not found")
        val returns = settings.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        if (returns.isEmpty()) throw PatchException("LauncherSettingsFragment.onCreatePreferences has no return")
        for (index in returns.reversed()) {
            settings.addInstructions(index, "invoke-static { p0 }, $ext->attach(Ljava/lang/Object;)V")
        }
    }
}

/**
 * Uninstalling: the launcher removes apps itself (deletePackageAsUser, DELETE_PACKAGES, a system permission), so every
 * uninstall failed ("Failed to delete ..."). Without the permission each app goes to Android's uninstall prompt
 * (see Uninstall); every entry point (home screen, drawer, folders, edit-mode selection) ends in these two methods.
 */
private val uninstallPatch = bytecodePatch {
    execute {
        val manager = mutableClassDefBy("Lcom/android/launcher/pkg/PackageDeleteManager;")
        for ((name, helper) in listOf(
            "uninstallApp" to "prompt(Ljava/lang/Object;)Z",
            "uninstallApps" to "prompts(Ljava/util/List;)Z",
        )) {
            val method = manager.methods.firstOrNull { it.name == name && it.returnType == "V" && it.parameterTypes.size == 1 }
                ?: throw PatchException("PackageDeleteManager.$name not found")
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $EXT/Uninstall;->$helper
                    move-result v0
                    if-eqz v0, :own
                    return-void
                """,
                ExternalLabel("own", method.implementation!!.instructions.first()),
            )
        }
    }
}

/**
 * Widget list: apps' shortcuts (activities for CREATE_SHORTCUT: "Settings shortcut", "Direct dial", "Directions", ...,
 * listed by Android's own launcher next to the widgets) never appeared. The list's filter asks OPPO's widget filter about
 * every entry first, and that answers no for an entry without a widget, so pre-installed apps that offer only shortcuts
 * were missing. A shortcut entry now gets Widgets.pickerShortcut's answer there; the rest of the filter is unchanged.
 */
private val widgetShortcutsPatch = bytecodePatch {
    execute {
        val check = mutableClassDefBy("Lcom/android/launcher3/model/WidgetsModel\$WidgetValidityCheck;")
        val test = check.methods.firstOrNull {
            it.name == "test" && it.parameterTypes == listOf("Lcom/android/launcher3/model/WidgetItem;")
        } ?: throw PatchException("WidgetsModel.WidgetValidityCheck.test(WidgetItem) not found")
        val instructions = test.implementation!!.instructions
        val call = instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_VIRTUAL &&
                ((it as ReferenceInstruction).reference as? MethodReference)?.let { r ->
                    r.definingClass == "Lcom/android/launcher3/OplusAppFilter;" && r.name == "shouldShowWidget"
                } == true
        }
        if (call < 0 || instructions.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT) {
            throw PatchException("WidgetValidityCheck.test does not ask OplusAppFilter.shouldShowWidget")
        }
        val result = (instructions[call + 1] as OneRegisterInstruction).registerA
        // The register after the answer is set again before its next read (const/4 0x0 in both versions).
        val next = instructions[call + 2]
        if (next.opcode != Opcode.CONST_4) throw PatchException("WidgetValidityCheck.test changed after shouldShowWidget")
        val free = (next as OneRegisterInstruction).registerA
        test.addInstructionsWithLabels(
            call + 2,
            """
                iget-object v$free, p1, Lcom/android/launcher3/model/WidgetItem;->activityInfo:Lcom/android/launcher3/pm/ShortcutConfigActivityInfo;
                if-eqz v$free, :widget
                iget-object v$free, p1, Lcom/android/launcher3/util/ComponentKey;->componentName:Landroid/content/ComponentName;
                invoke-static { v$free }, $EXT/Widgets;->pickerShortcut(Landroid/content/ComponentName;)Z
                move-result v$result
            """,
            ExternalLabel("widget", test.implementation!!.instructions[call + 2]),
        )

        // The list's cells only answered for widgets: a shortcut is picked up by touch and hold like a widget (dropped on
        // the home screen it opens the app's shortcut screen, as in Android's launcher) and a tap shows Launcher3's
        // "Touch & hold to move a shortcut." hint. The page thumbnails take only widgets (a shortcut dropped there was lost).
        val sheet = mutableClassDefBy("Lcom/android/launcher3/views/OplusWidgetsFullSheet;")
        val pendingWidget = "Lcom/android/launcher3/widget/PendingAddWidgetInfo;"
        val pendingShortcut = "Lcom/android/launcher3/widget/PendingAddShortcutInfo;"
        val longClick = sheet.methods.firstOrNull { it.name == "onLongClick" && it.parameterTypes == listOf("Landroid/view/View;") }
            ?: throw PatchException("OplusWidgetsFullSheet.onLongClick not found")
        val widgetCheck = longClick.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.INSTANCE_OF && ((it as ReferenceInstruction).reference as? TypeReference)?.type == pendingWidget
        }
        if (widgetCheck < 0) throw PatchException("OplusWidgetsFullSheet.onLongClick does not check for a widget")
        val isWidget = longClick.implementation!!.instructions[widgetCheck] as TwoRegisterInstruction
        longClick.replaceInstruction(
            widgetCheck,
            "instance-of v${isWidget.registerA}, v${isWidget.registerB}, Lcom/android/launcher3/PendingAddItemInfo;",
        )

        val click = sheet.methods.firstOrNull { it.name == "onClick" && it.parameterTypes == listOf("Landroid/view/View;") }
            ?: throw PatchException("OplusWidgetsFullSheet.onClick not found")
        click.addInstructionsWithLabels(
            0,
            """
                invoke-virtual { p1 }, Landroid/view/View;->getTag()Ljava/lang/Object;
                move-result-object v0
                instance-of v0, v0, $pendingShortcut
                if-eqz v0, :own
                invoke-super { p0, p1 }, Lcom/android/launcher3/widget/BaseWidgetSheet;->onClick(Landroid/view/View;)V
                return-void
            """,
            ExternalLabel("own", click.implementation!!.instructions.first()),
        )

        val dropped = sheet.methods.firstOrNull { it.name == "onDropCompleted" && it.parameterTypes.size == 3 }
            ?: throw PatchException("OplusWidgetsFullSheet.onDropCompleted not found")
        dropped.addInstructionsWithLabels(
            0,
            """
                instance-of v0, p1, Lcom/android/launcher/pagepreview/PagePreviewListView;
                if-eqz v0, :own
                iget-object v0, p2, Lcom/android/launcher3/DropTarget${'$'}DragObject;->dragInfo:Lcom/android/launcher3/model/data/ItemInfo;
                instance-of v0, v0, $pendingWidget
                if-nez v0, :own
                invoke-direct { p0, p3 }, Lcom/android/launcher3/views/OplusWidgetsFullSheet;->notifyOnDropWidgetToDesktop(Z)V
                return-void
            """,
            ExternalLabel("own", dropped.implementation!!.instructions.first()),
        )

        val thumbnails = mutableClassDefBy("Lcom/android/launcher/pagepreview/PagePreviewListView;")
        val accept = thumbnails.methods.firstOrNull { it.name == "acceptDrop" && it.parameterTypes.size == 1 }
            ?: throw PatchException("PagePreviewListView.acceptDrop not found")
        accept.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p1, Lcom/android/launcher3/DropTarget${'$'}DragObject;->dragInfo:Lcom/android/launcher3/model/data/ItemInfo;
                instance-of v0, v0, $pendingShortcut
                if-eqz v0, :own
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("own", accept.implementation!!.instructions.first()),
        )
    }
}

/**
 * Selecting apps in edit mode: every app after the first kept its grey check until the page redrew (the selection itself
 * worked). The check's animation marks the icon for redrawing, but that did not reach the page; the icon's parents are
 * now asked to redraw too (see Views.invalidateUp).
 */
private val selectCheckPatch = bytecodePatch {
    execute {
        // 15.6.24 redraws the check through its render manager (no such method; not seen there).
        if (packageMetadata.versionName == "15.6.24") return@execute
        val renderer = mutableClassDefBy("Lcom/android/launcher3/iconrender/impl/SelectStateIconRenderer;")
        val request = renderer.methods.firstOrNull { it.name == "requestInvalidate" && it.returnType == "V" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("SelectStateIconRenderer.requestInvalidate not found")
        val instructions = request.implementation!!.instructions
        val call = instructions.indexOfFirst {
            it.opcode == Opcode.INVOKE_VIRTUAL &&
                ((it as ReferenceInstruction).reference as? MethodReference)?.let { r -> r.name == "invalidate" && r.parameterTypes.isEmpty() } == true
        }
        if (call < 0) throw PatchException("SelectStateIconRenderer.requestInvalidate: no invalidate() call")
        val view = (instructions[call] as FiveRegisterInstruction).registerC
        request.replaceInstruction(call, "invoke-static { v$view }, $EXT/Views;->invalidateUp(Ljava/lang/Object;)V")
    }
}

/** One version's generated lists (.github/scripts/coloros-launcher/hidden.py). */
private class HiddenLists(
    val members: Set<String>,
    val constructors: Set<String>,
    val superConstructors: Map<String, String>,
    val superclasses: Map<String, Pair<String, List<String>>>,
    val inaccessible: Set<String> = emptySet(),
    // 15.6.24 copies some new objects to another register before their constructor (see redirectConstructors).
    val followMoves: Boolean = false,
)

/** 16.4.30's lists were checked against Android 15 (and 13, 16 / 17, canary), 15.6.24's against 11, 15, 16, 17, canary. */
private fun hiddenLists(version: String) = when (version) {
    "15.6.24" -> HiddenLists(
        HIDDEN_MEMBERS_15, HIDDEN_CONSTRUCTORS_15, SUPER_CONSTRUCTORS_15, HIDDEN_SUPERCLASSES_15,
        INACCESSIBLE_INTERFACES_15, followMoves = true,
    )
    else -> HiddenLists(
        HIDDEN_MEMBERS + HIDDEN_MEMBERS_16_17 + HIDDEN_MEMBERS_13 + HIDDEN_MEMBERS_CANARY,
        HIDDEN_CONSTRUCTORS + HIDDEN_CONSTRUCTORS_16_17 + HIDDEN_CONSTRUCTORS_13 + HIDDEN_CONSTRUCTORS_CANARY,
        SUPER_CONSTRUCTORS, HIDDEN_SUPERCLASSES, INACCESSIBLE_INTERFACES,
    )
}

/**
 * App classes that implement an Android interface the phone keeps private (AnimationHandler.AnimationFrameCallback on
 * Android 11 and One UI 5.1) do not load: they lose it, on every phone. The calls that took them as one go through
 * Members, which needs only their methods. Returns the classes changed.
 */
private fun BytecodePatchContext.dropInterfaces(interfaces: Set<String>): Int {
    if (interfaces.isEmpty()) return 0
    val targets = mutableListOf<String>()
    classDefForEach { c ->
        if (!c.type.startsWith("Lorg/ungoogled/") && c.interfaces.any { it in interfaces }) targets += c.type
    }
    for (type in targets) mutableClassDefBy(type).interfaces.removeAll(interfaces)
    return targets.size
}

/**
 * The System Launcher is a system app built against the full Android framework; an installed app may not link to its
 * hidden parts. Each hidden member it uses goes through Members (used where Android allows it, "absent" elsewhere),
 * each `new` of a class an app may not construct through Members.construct, and subclasses of such classes are rebased.
 * The lists are generated per version (.github/scripts/coloros-launcher/hidden.py).
 */
private val hiddenApisPatch = bytecodePatch(
    description = "Answers Android's hidden parts where an installed app may not use them.",
) {
    extendWith(COLOROS_LAUNCHER_EXTENSION)

    execute {
        val lists = hiddenLists(packageMetadata.versionName)
        // Java 21 library calls first (see Java21): 15.6.24's lists name some of them too, and Members could only
        // answer those "absent" where the phone lacks them.
        val java = redirectNewerJavaCalls("$EXT/Java21;")
        if (java == 0) throw PatchException("no newer Java library calls found")
        val (calls, fields) = redirectHiddenMembers("$EXT/Members;", "$EXT/Hidden;", lists.members + REFUSED_USER_CALLS)
        val constructors = redirectConstructors(
            "$EXT/Members;", "$EXT/Hidden;", lists.constructors, followMoves = lists.followMoves,
        )
        val superCalls = substituteSuperConstructors(lists.superConstructors)
        val rebased = rebaseSubclasses(lists.superclasses)
        val dropped = dropInterfaces(lists.inaccessible)
        if (dropped == 0 && lists.inaccessible.isNotEmpty()) throw PatchException("no classes implement ${lists.inaccessible}")
        skipCastOfNull("$EXT/Hidden;")
        log.info("hidden members: $calls calls, $fields fields; constructors: $constructors; super calls: $superCalls; rebased classes: $rebased; interfaces dropped: $dropped; java: $java")
        if (calls < 100) throw PatchException("only $calls hidden member calls found")
        if (constructors == 0) throw PatchException("hidden constructors not found")
        if (rebased == 0) throw PatchException("no subclasses of hidden Android classes found")
    }
}

/**
 * On a phone with a work profile 16.4.30's first start switches it to drawer mode for OPPO's managed-device (COPE)
 * layout, without building the drawer's tables: its loader then died on every start and the home screen stayed empty.
 * It keeps the mode it is in. (15.6.24 builds them: unchanged.)
 */
private val workProfilePatch = bytecodePatch {
    execute {
        if (packageMetadata.versionName == "15.6.24") return@execute
        val switch = mutableClassDefBy("Lcom/android/launcher3/aer/ProvisionManager;").methods.firstOrNull {
            it.name == "switchDrawerModeWhenFirstCOPE" && it.returnType == "V" && it.parameterTypes.isEmpty()
        } ?: throw PatchException("ProvisionManager.switchDrawerModeWhenFirstCOPE() not found")
        switch.addInstructions(0, "return-void")
    }
}

/**
 * Dark or light system bar icons, labels and page dots follow the wallpaper. ColorOS's own system UI shades the
 * navigation bar to the wallpaper: the launcher never sets its light-bar flag itself, so elsewhere the buttons stayed
 * white on any wallpaper and looked gone on a light one. Its nav bar check now sets it from the bottom of the wallpaper
 * (Wallpapers.navigationBar), and its theme follows the wallpaper's dark-text hint on Android 11 as from 12 on. Labels,
 * page dots and the status bar come from the wallpaper image, which Android doesn't let an installed app read: it gets
 * one of the wallpaper's colors instead (Wallpapers.getDrawable).
 */
private val wallpaperBrightnessPatch = bytecodePatch {
    execute {
        val themes = mutableClassDefBy("Lcom/android/launcher3/util/Themes;")
        val pick = themes.methods.firstOrNull {
            it.name == "getActivityThemeRes" && it.returnType == "I" &&
                it.parameterTypes.map { t -> t.toString() } == listOf("Landroid/content/Context;", "I")
        } ?: throw PatchException("Themes.getActivityThemeRes(Context, int) not found")
        val gate = pick.implementation!!.instructions.withIndex().filter { (_, insn) ->
            insn.opcode == Opcode.SGET_BOOLEAN &&
                ((insn as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == "Lcom/android/launcher3/Utilities;" && it.name == "ATLEAST_S"
                }
        }
        if (gate.size != 1) throw PatchException("Themes.getActivityThemeRes reads Utilities.ATLEAST_S ${gate.size} times")
        val (index, insn) = gate.single()
        val register = (insn as OneRegisterInstruction).registerA
        pick.replaceInstruction(index, if (register < 16) "const/4 v$register, 0x1" else "const/16 v$register, 0x1")

        val resolver = mutableClassDefBy("Lcom/android/launcher/wallpaper/WallpaperResolver;")
        if (resolver.fields.none { it.name == "mIsNavigationBarBright" } || resolver.fields.none { it.name == "mNavBarHeight" }) {
            throw PatchException("WallpaperResolver has no mIsNavigationBarBright / mNavBarHeight")
        }
        val navigationBar = resolver.methods.firstOrNull {
            it.name == "resolveNavigationBarBrightness" && it.returnType == "V" &&
                it.parameterTypes.map { t -> t.toString() } == listOf("Lcom/android/launcher/Launcher;", "Landroid/graphics/Bitmap;")
        } ?: throw PatchException("WallpaperResolver.resolveNavigationBarBrightness(Launcher, Bitmap) not found")
        navigationBar.addInstructions(
            0,
            """
                invoke-static { p0, p1, p2 }, $EXT/Wallpapers;->navigationBar(Ljava/lang/Object;Landroid/app/Activity;Landroid/graphics/Bitmap;)V
                return-void
            """,
        )

        // The bars' states: the window's and the home screen's carry the navigation bar decided above (see
        // Wallpapers.uiState). The home screen's is the one set from the status bar's brightness.
        val controllerType = "Lcom/android/launcher3/util/SystemUiController;"
        val controller = mutableClassDefBy(controllerType)
        val update = controller.methods.firstOrNull {
            it.name == "updateUiState" && it.returnType == "V" && it.parameterTypes.map { t -> t.toString() } == listOf("I", "I")
        } ?: throw PatchException("SystemUiController.updateUiState(int, int) not found")
        if (controller.fields.none { it.name == "mWindow" && it.type == "Landroid/view/Window;" } ||
            controller.fields.none { it.name == "mStates" && it.type == "[I" }
        ) {
            throw PatchException("SystemUiController has no mWindow / mStates")
        }
        if (update.implementation!!.registerCount - 3 < 2) throw PatchException("updateUiState has no spare registers")
        update.addInstructions(
            0,
            """
                iget-object v0, p0, $controllerType->mWindow:Landroid/view/Window;
                iget-object v1, p0, $controllerType->mStates:[I
                invoke-static { v0, v1, p1, p2 }, $EXT/Wallpapers;->uiState(Landroid/view/Window;[III)V
                return-void
            """,
        )
        val homeScreen = mutableListOf<Pair<String, String>>() // class, method (name + descriptor)
        val signature = { m: com.android.tools.smali.dexlib2.iface.Method -> m.name + m.parameterTypes.joinToString("") + m.returnType }
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in c.methods) {
                val refs = method.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                    ?: continue
                if (refs.any { it.definingClass == controllerType && it.name == "updateUiState" } &&
                    refs.any { it.definingClass == "Lcom/android/launcher/wallpaper/LauncherWallpaperManager;" && it.name == "isStatusBarWpBright" }
                ) {
                    homeScreen += c.type to signature(method)
                }
            }
        }
        // The launcher's own pages paint the navigation bar opaque and leave its buttons to ColorOS (see Wallpapers.barFlags).
        val toolbar = mutableClassDefBy("Lcom/android/common/util/ToolbarUtils;")
        val setFlags = toolbar.methods.firstOrNull {
            it.name == "setSystemUiVisibility" && it.returnType == "V" &&
                it.parameterTypes.map { t -> t.toString() } == listOf("Landroid/view/View;", "I")
        } ?: throw PatchException("ToolbarUtils.setSystemUiVisibility(View, int) not found")
        setFlags.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $EXT/Wallpapers;->barFlags(Landroid/view/View;I)I
                move-result p1
            """,
        )
        var homeBars = 0
        for ((type, sig) in homeScreen) {
            val method = mutableClassDefBy(type).methods.first { signature(it) == sig }
            val reads = method.implementation!!.instructions.withIndex().filter { (_, insn) ->
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                    it.definingClass == "Lcom/android/launcher/wallpaper/LauncherWallpaperManager;" && it.name == "isStatusBarWpBright"
                } == true
            }.map { it.index }
            for (index in reads.reversed()) method.addInstructionsAtLabel(index, "invoke-static { }, $EXT/Wallpapers;->homeBars()V")
            homeBars += reads.size
        }
        if (homeBars == 0) throw PatchException("no home screen bars set from the status bar's brightness")
        log.info("home screen bar states: $homeBars")

        val images = redirectCalls(
            mapOf(
                "Landroid/app/WallpaperManager;->getDrawable()Landroid/graphics/drawable/Drawable;" to
                    "$EXT/Wallpapers;->getDrawable(Landroid/app/WallpaperManager;)Landroid/graphics/drawable/Drawable;",
            ),
        )
        log.info("wallpaper images: $images")
        if (images == 0) throw PatchException("no WallpaperManager.getDrawable() calls found")
    }
}

/**
 * The edit bar's Wallpapers, Icons and Personalization buttons open ColorOS's own apps and did nothing elsewhere:
 * Wallpapers opens Android's wallpaper pickers, the other two are left out (see Personalization).
 */
private val personalizationPatch = bytecodePatch {
    execute {
        val adapter = mutableClassDefBy("Lcom/android/launcher/togglebar/adapter/ToggleBarMainUIAdapter;")
        val wallpaper = adapter.methods.firstOrNull { method ->
            method.returnType == "Landroid/content/Intent;" && method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "android.intent.action.SET_WALLPAPER"
            } == true
        } ?: throw PatchException("ToggleBarMainUIAdapter's wallpaper intent not found")
        val pick = wallpaper.implementation!!.instructions.withIndex().filter { (_, insn) ->
            ((insn as? ReferenceInstruction)?.reference as? StringReference)?.string == "com.oplus.wallpapers"
        }
        if (pick.size != 1) throw PatchException("the wallpaper intent names com.oplus.wallpapers ${pick.size} times")
        val (index, insn) = pick.single()
        val register = (insn as OneRegisterInstruction).registerA
        wallpaper.addInstructions(
            index + 1,
            """
                invoke-static { v$register }, $EXT/Personalization;->ifInstalled(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$register
            """,
        )

        // Every isIgnoreItem(simpleMode, viewId, wallpaperDisabled, launcher) answer is also asked of Personalization.
        val controller = mutableClassDefBy("Lcom/android/launcher/togglebar/controller/ToggleBarMainUIController;")
        val isIgnore = { r: MethodReference ->
            r.definingClass == controller.type && r.name == "isIgnoreItem" && r.returnType == "Z" &&
                r.parameterTypes.map { it.toString() } == listOf("Z", "I", "Z", "Lcom/android/launcher/Launcher;")
        }
        var calls = 0
        for (method in controller.methods) {
            val instructions = method.implementation?.instructions ?: continue
            for (index in instructions.indices.reversed()) {
                val call = instructions[index]
                val r = (call as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (!isIgnore(r) || call !is FiveRegisterInstruction) continue
                val result = instructions.getOrNull(index + 1)
                if (result?.opcode != Opcode.MOVE_RESULT) throw PatchException("isIgnoreItem's answer is not kept")
                val answer = (result as OneRegisterInstruction).registerA
                val id = call.registerE // (this, simpleMode, viewId, wallpaperDisabled, launcher)
                val launcher = call.registerG
                if (maxOf(answer, id, launcher) > 15) throw PatchException("isIgnoreItem call uses high registers")
                method.addInstructions(
                    index + 2,
                    """
                        invoke-static { v$answer, v$id, v$launcher }, $EXT/Personalization;->ignore(ZILandroid/content/Context;)Z
                        move-result v$answer
                    """,
                )
                calls++
            }
        }
        if (calls == 0) throw PatchException("no ToggleBarMainUIController.isIgnoreItem calls found")
        log.info("edit bar: wallpaper intent, $calls item checks")
    }
}

/**
 * The launcher's settings link to ColorOS Settings pages ("System navigation" under "You might be looking for", the
 * taskbar settings' navigation entry), which did nothing elsewhere: each intent its settings start is checked first and
 * a ColorOS page this phone lacks becomes Android's own (see Links).
 */
private val settingsLinksPatch = bytecodePatch {
    execute {
        val starts = mutableListOf<Pair<String, String>>()
        val signature = { m: com.android.tools.smali.dexlib2.iface.Method -> m.name + m.parameterTypes.joinToString("") + m.returnType }
        val isStart = { r: MethodReference? ->
            r != null && r.name == "startActivity" && r.returnType == "V" &&
                r.parameterTypes.map { it.toString() } == listOf("Landroid/content/Intent;")
        }
        classDefForEach { c ->
            if (!c.type.startsWith("Lcom/android/launcher/settings/")) return@classDefForEach
            for (method in c.methods) {
                if (method.implementation?.instructions?.any { isStart((it as? ReferenceInstruction)?.reference as? MethodReference) } == true) {
                    starts += c.type to signature(method)
                }
            }
        }
        var calls = 0
        for ((type, sig) in starts) {
            val method = mutableClassDefBy(type).methods.first { signature(it) == sig }
            val sites = method.implementation!!.instructions.withIndex().filter { (_, insn) ->
                isStart((insn as? ReferenceInstruction)?.reference as? MethodReference)
            }
            for ((index, insn) in sites.reversed()) {
                val receiver = if (insn.opcode == Opcode.INVOKE_STATIC || insn.opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
                val intent = when (insn) {
                    is FiveRegisterInstruction -> if (receiver == 0) insn.registerC else insn.registerD
                    is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction -> insn.startRegister + receiver
                    else -> throw PatchException("unexpected startActivity call in $type")
                }
                method.addInstructionsAtLabel(index, "invoke-static/range { v$intent .. v$intent }, $EXT/Links;->settings(Landroid/content/Intent;)V")
                calls++
            }
        }
        log.info("settings links: $calls startActivity calls")
        if (calls < 2) throw PatchException("only $calls startActivity calls in the launcher's settings")
    }
}

/** What a system home screen may do and an installed app may not. */
private val systemPrivilegesPatch = bytecodePatch(
    description = "Keeps its options locally and registers widgets with Android's public calls.",
) {
    extendWith(COLOROS_LAUNCHER_EXTENSION)

    execute {
        val receivers = exportDynamicReceivers(RUNTIME)
        val settings = redirectCalls(SETTINGS_CALLS)
        val windows = redirectCalls(WINDOW_CALLS)
        val reflection = redirectCalls(REFLECTION_CALLS)
        val guarded = redirectCalls(GUARDED_CALLS)
        log.info("receivers: $receivers, settings calls: $settings, windows: $windows, reflection: $reflection, guarded: $guarded")
        if (settings < 50) throw PatchException("only $settings settings calls found")
        if (guarded < 20) throw PatchException("only $guarded content observer / user calls found")

        // A widget view made ahead of time is registered for updates through the host's public createView (see
        // Widgets.listen): the host's onCreateView then hands that view back instead of making a new one. The method
        // only calls its private overload with no view and has no spare register, so it is rebuilt with one.
        val host = mutableClassDefBy("Lcom/android/launcher3/widget/LauncherAppWidgetHost;")
        val hostView = "Lcom/android/launcher3/widget/LauncherAppWidgetHostView;"
        val create = host.methods.firstOrNull {
            it.name == "onCreateView" && it.returnType == hostView && it.parameterTypes.map { t -> t.toString() } ==
                listOf("Landroid/content/Context;", "I", "Landroid/appwidget/AppWidgetProviderInfo;")
        } ?: throw PatchException("LauncherAppWidgetHost.onCreateView(Context, int, AppWidgetProviderInfo) not found")
        val overloads = create.implementation!!.instructions.mapNotNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { it.name == "onCreateView" }
                ?.let { insn.opcode to it }
        }
        if (overloads.isEmpty()) {
            // 15.6.24 makes its views itself and has them registered by Android's public AppWidgetHost.createView.
            val public = host.methods.any { method ->
                method.name == "createView" && method.implementation?.instructions?.any { insn ->
                    ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.definingClass == "Landroid/appwidget/AppWidgetHost;" && it.name == "createView"
                    } == true
                } == true
            }
            if (!public) throw PatchException("LauncherAppWidgetHost makes widget views neither way it is known to")
            return@execute
        }
        val (opcode, target) = overloads.singleOrNull()
            ?: throw PatchException("LauncherAppWidgetHost.onCreateView does not call its overload once")
        if (target.parameterTypes.map { it.toString() } != listOf("Landroid/content/Context;", hostView, "I")) {
            throw PatchException("unexpected onCreateView overload $target")
        }
        val parameters = create.parameterTypes.map { it.toString() }
        host.methods.remove(create)
        host.methods.add(
            ImmutableMethod(
                create.definingClass, create.name, parameters.map { ImmutableMethodParameter(it, null, null) },
                create.returnType, create.accessFlags, null, null, MutableMethodImplementation(5),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p2 }, $EXT/Widgets;->pending(I)Landroid/appwidget/AppWidgetHostView;
                        move-result-object v0
                        if-nez v0, :pending
                        const/4 v0, 0x0
                        ${opcode.name.lowercase().replace('_', '-')} { p0, p1, v0, p2 }, $target
                        move-result-object v0
                        return-object v0
                        :pending
                        check-cast v0, $hostView
                        return-object v0
                    """,
                )
            },
        )
    }
}

/** Another version of the app or too old an Android stops here with a clear message (see supportedVersionCheck). */
private val versionCheckPatch = supportedVersionCheck(COMPATIBILITY_COLOROS_LAUNCHER)

/**
 * On OPPO / OnePlus / realme phones and custom ROMs made for them (their oplus-fwk.jar), the phone's own ColorOS
 * classes load before the extension's stand-ins and refuse an installed app (issue #155): the app uses private copies
 * of its stand-ins instead (see privatizeFrameworkClasses). OplusViewPager stays: a layout names it.
 */
// Measured against 16.4.30 and 15.6.24: 233 classes.
private val privateClassesPatch = privateFrameworkClassesPatch(
    "OnePlusSystemLauncher", COLOROS_LAUNCHER_EXTENSION, minimum = 200,
    keep = setOf("Lcom/oplus/widget/OplusViewPager;"),
)

/**
 * Off ColorOS the System Launcher dies at once on com.oplus.dispatch.WorkDispatch (a ColorOS framework class). The
 * extension carries stand-ins for the ColorOS classes it uses (generated by .github/scripts/coloros-launcher): ColorOS's
 * work dispatcher runs on Android's threads, its render-thread animators on the UI thread, its partition paths point
 * nowhere; on an OPPO / OnePlus / realme phone the framework's own classes load first, so they never run there. Its
 * hidden Android API goes through Members (see hiddenApisPatch), its spring animations get their frames from the
 * Choreographer (Frames). Recents and navigation gestures stay the phone's own: Android gives them only to the system's
 * home app.
 */
@Suppress("unused")
val enableNonColorOsPatch = bytecodePatch(
    name = "Enable non-ColorOS ROM support",
    description = "Makes OnePlus System Launcher usable as a home screen on non-ColorOS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_COLOROS_LAUNCHER)
    dependsOn(
        versionCheckPatch, privateClassesPatch, manifestPatch, olderAndroidResourcesPatch, hiddenApisPatch,
        systemPrivilegesPatch, workProfilePatch, wallpaperBrightnessPatch, personalizationPatch, settingsLinksPatch,
        nightBackgroundsPatch, morphIconStylePatch, lockScreenPatch, selectCheckPatch, workspaceRedrawPatch, backgroundsPatch,
        uninstallPatch, widgetShortcutsPatch,
    )
    extendWith(COLOROS_LAUNCHER_EXTENSION)
    // Authorities OPPO's SDKs build from the package name. OPPO's badge provider (com.android.badge) moves like the
    // other names: Honor's launcher declares it too, and Android refused the install there (issue #232).
    dependsOn(
        org.ungoogled.patches.shared.launcherCoexistencePatch(
            keep = setOf(
                "com.android.launcher.epona", "com.android.launcher.custom.DictProvider",
                "com.android.launcher.upk.FileProvider",
            ),
        ),
    )
}
