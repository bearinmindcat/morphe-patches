package org.ungoogled.patches.redmagicgamespace.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.redmagicgamespace.GAME_SPACE_HIDDEN_MEMBERS
import org.ungoogled.patches.redmagicgamespace.RedMagicGameSpaceConstants.COMPATIBILITY_REDMAGIC_GAME_SPACE
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectMembers
import org.w3c.dom.Element

internal const val REDMAGIC_GAME_SPACE_EXTENSION = "extensions/redmagicgamespace.mpe"
private const val EXT = "Lorg/ungoogled/redmagicgamespace"
private const val LOBBY = "cn.nubia.gamelauncher.activity.GameSpaceActivity"

private val log = java.util.logging.Logger.getLogger("RedMagicGameSpace")

/**
 * On RedMagic OS the physical Game Space switch opens Game Space; the app has no launcher entry. Its lobby gets one,
 * and shows in recents like any app. Game time (lobby, "Game Achievements") comes from Android's usage statistics,
 * which RedMagic OS hands its own app; elsewhere it needs "Usage access", which an app can only be given once it
 * asks for it.
 */
private val launcherPatch = resourcePatch(
    description = "Opens RedMagic Game Space from the app drawer.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val activities = manifest.getElementsByTagName("activity")
            val lobby = (0 until activities.length).map { activities.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == LOBBY }
                ?: throw PatchException("$LOBBY not in the manifest")
            lobby.removeAttribute("android:excludeFromRecents")
            lobby.appendChild(
                manifest.createElement("intent-filter").apply {
                    appendChild(manifest.createElement("action").apply { setAttribute("android:name", "android.intent.action.MAIN") })
                    appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.LAUNCHER") })
                },
            )
            val application = manifest.getElementsByTagName("application").item(0) as Element
            manifest.documentElement.insertBefore(
                manifest.createElement("uses-permission").apply {
                    setAttribute("android:name", "android.permission.PACKAGE_USAGE_STATS")
                },
                application,
            )
        }
    }
}

/**
 * The lobby's per-game control panel (performance modes, touch and shoulder-trigger settings for the selected game) is
 * RedMagic OS's own (ZTE's performance service): off it the button opened nothing. It leaves the lobby, and its group,
 * whose visibility would bring it back.
 */
private val lobbyPatch = resourcePatch(
    description = "Hides the RedMagic-only game control panel button.",
) {
    execute {
        document("res/layout/game_lobby_layout.xml").use { layout ->
            val all = layout.getElementsByTagName("*")
            val elements = (0 until all.length).map { all.item(it) as Element }
            val control = elements.firstOrNull { it.getAttribute("android:id") == "@id/lobby_control" }
                ?: throw PatchException("lobby_control not in game_lobby_layout")
            control.setAttribute("android:visibility", "gone")
            val group = elements.firstOrNull { it.getAttribute("android:id") == "@id/lobby_group" }
                ?: throw PatchException("lobby_group not in game_lobby_layout")
            val ids = group.getAttribute("app:constraint_referenced_ids")
            if ("lobby_control" !in ids) throw PatchException("lobby_group no longer names lobby_control: $ids")
            group.setAttribute(
                "app:constraint_referenced_ids",
                ids.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "lobby_control" }.joinToString(","),
            )
        }
    }
}

/** ShortcutController.supportAddShortcut(): the lobby's "G" button, which asks ZTE's launcher for a Game Space icon. */
private object AddShortcutFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamelauncher/controller/ShortcutController;",
    name = "supportAddShortcut",
    returnType = "Z",
    parameters = listOf(),
)

/** ShortcutController.resetShortcutTips(): shows the "G" button's tip again when the lobby comes back. */
private object ShortcutTipsFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamelauncher/controller/ShortcutController;",
    name = "resetShortcutTips",
    returnType = "V",
    parameters = listOf(),
)

/**
 * The "G" button asks ZTE's own launcher (com.zte.mifavor.launcher) for a Game Space icon; the port has its own. The
 * button and its tip stay hidden.
 */
private val shortcutPatch = bytecodePatch(
    description = "Hides the button that adds Game Space to ZTE's launcher.",
) {
    execute {
        AddShortcutFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )
        ShortcutTipsFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual { p0 }, Lcn/nubia/gamelauncher/controller/ShortcutController;->hideShortcutTips()V
                return-void
            """,
        )
    }
}

/** SummaryFragment.onViewCreated: "Game Achievements", the first page of Game Space Center. */
private object AchievementsFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamecenter/settings/summary/SummaryFragment;",
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/** AppUsageStatsHelper.queryUsageStatsZte(context, volume, package, user): a game's size, through a hidden call. */
private object AppSizeFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/common/helper/AppUsageStatsHelper;",
    name = "queryUsageStatsZte",
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;", "Landroid/os/UserHandle;"),
)

/**
 * Game time and sizes come from Android's usage and storage statistics, which RedMagic OS hands its own app (the size
 * through a hidden call an ordinary app may not make). Game Space Center asks once for "Usage access", and a game's
 * size is read through the public call.
 */
private val usageStatsPatch = bytecodePatch(
    description = "Asks for usage access for game time and reads game sizes the public way.",
) {
    extendWith(REDMAGIC_GAME_SPACE_EXTENSION)

    execute {
        AchievementsFingerprint.method.addInstructions(0, "invoke-static { p1 }, $EXT/GameSpace;->askUsageAccess(Landroid/view/View;)V")
        AppSizeFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1, p2, p3, p4 }, $EXT/GameSpace;->appSize(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Landroid/os/UserHandle;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """,
        )
    }
}

/** NubiaTrackManager.init(Context): starts the thread that hands usage events to RedMagic OS's data collection. */
private object TrackInitFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamelauncher/util/NubiaTrackManager;",
    name = "init",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

/**
 * Game Space reports what is tapped (fan, cards, settings) to RedMagic OS's data collection service (com.zte.analytics
 * or cn.nubia.owlsystem). Off RedMagic OS that service is missing and the reporting thread waited for it forever; it
 * is not started, so events are dropped at once.
 */
private val trackingPatch = bytecodePatch(
    description = "Drops RedMagic Game Space's usage events.",
) {
    execute {
        TrackInitFingerprint.method.addInstructions(0, "return-void")
    }
}

private const val BITMAP_RECYCLE = "Landroid/graphics/Bitmap;->recycle()V"

/**
 * Game Space frees bitmaps by hand (Bitmap.recycle) that another view may still draw -- the lobby's atmosphere image
 * is also kept in its image cache -- and the next frame died with "Canvas: trying to use a recycled bitmap" when the
 * lobby reloaded its game list (seen on returning from a game). Its own recycle calls are left to Android's garbage
 * collector, which frees bitmaps anyway.
 */
private val bitmapPatch = bytecodePatch(
    description = "Stops RedMagic Game Space from freeing bitmaps it still draws.",
) {
    extendWith(REDMAGIC_GAME_SPACE_EXTENSION)

    execute {
        fun Any.isRecycle() = this is ReferenceInstruction && opcode == Opcode.INVOKE_VIRTUAL &&
            (reference as? MethodReference)?.let { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" } == BITMAP_RECYCLE
        val sites = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (!c.type.startsWith("Lcn/nubia/")) return@classDefForEach
            for (m in c.methods) if (m.implementation?.instructions?.any { it.isRecycle() } == true) sites += c.type to m
        }
        var moved = 0
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                if (!insn.isRecycle()) continue
                mutable.replaceInstruction(index, "invoke-static { v${(insn as FiveRegisterInstruction).registerC} }, $EXT/GameSpace;->keep(Landroid/graphics/Bitmap;)V")
                moved++
            }
        }
        log.info("Bitmap.recycle calls: $moved")
        // Measured against 15.0.000.001: the atmosphere/wallpaper images, the settings widgets, the image loader.
        if (moved < 5) throw PatchException("only $moved Bitmap.recycle calls found")
    }
}

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

/**
 * Game Space keeps its state (game mode, panel switches, per-game options) in Android's settings tables, where the
 * rest of RedMagic OS reads it, and its first write at start-up (Settings.Global, which needs WRITE_SECURE_SETTINGS)
 * killed it. Every settings call goes through LocalSettings, which keeps a refused write in the app's own preferences.
 */
private val settingsPatch = bytecodePatch(
    description = "Keeps the settings RedMagic Game Space may not write in its own storage.",
) {
    extendWith(REDMAGIC_GAME_SPACE_EXTENSION)

    execute {
        val calls = buildMap {
            for (table in listOf("Global", "Secure", "System")) {
                for ((shape, suffix) in SETTINGS_SHAPES) {
                    put("Landroid/provider/Settings\$$table;->$shape", "$EXT/LocalSettings;->${table.lowercase()}$suffix${shape.substring(shape.indexOf('('))}")
                }
            }
            put(
                "Landroid/provider/Settings\$System;->putStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;I)Z",
                "$EXT/LocalSettings;->systemPutStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;I)Z",
            )
        }
        val moved = redirectCalls(calls)
        log.info("Settings calls: $moved")
        // Measured against 15.0.000.001: 487 calls.
        if (moved < 300) throw PatchException("only $moved Settings calls found")
    }
}

/**
 * Game Space is built as a RedMagic OS system app against Android's own framework (and bundles launcher and recents
 * code): it calls hidden Android members an ordinary app may not use, some of which Android 15 no longer has. Each
 * such call or field access goes through RedMagic (by reflection, under Android's rules for apps), which answers
 * "not there" where the phone refuses.
 */
private val hiddenMembersPatch = bytecodePatch(
    description = "Answers the hidden Android calls RedMagic Game Space may not make.",
) {
    extendWith(REDMAGIC_GAME_SPACE_EXTENSION)

    execute {
        val (calls, fields) = redirectMembers("$EXT/RedMagic;", "$EXT/RedMagicShims;", GAME_SPACE_HIDDEN_MEMBERS) { _, _ -> false }
        log.info("Hidden members: $calls calls, $fields field accesses")
        // Measured against 15.0.000.001: 476 calls and 79 field accesses.
        if (calls < 100) throw PatchException("only $calls hidden calls and $fields field accesses found")
    }
}

/**
 * Off RedMagic OS Game Space dies at start-up writing a global setting, then would on ZTE's framework classes
 * (com.zte.feature.Feature, GameAssistWrapper, ...) and hidden Android calls. The extension carries stand-ins for the
 * 12 ZTE/Nubia classes it uses (generated by .github/scripts/redmagic-standins); on a RedMagic phone the framework's
 * own classes load first, so they never run there. Its ZTE feature switches (Fallbacks.feature) turn off what needs
 * RedMagic hardware, RedMagic OS's system services or its other apps: cooling fan, shoulder triggers, Mora, playing by
 * mouse/gamepad/mirror, host mode, Super Base, and the Game Space Center pages RedMagic OS applies in games.
 */
@Suppress("unused")
val enableNonRedMagicPatch = bytecodePatch(
    name = "Enable non-RedMagic ROM support",
    description = "Makes RedMagic Game Space usable on non-RedMagic ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_REDMAGIC_GAME_SPACE)
    dependsOn(launcherPatch, lobbyPatch, shortcutPatch, usageStatsPatch, bitmapPatch, trackingPatch, settingsPatch, hiddenMembersPatch)
    extendWith(REDMAGIC_GAME_SPACE_EXTENSION)
}
