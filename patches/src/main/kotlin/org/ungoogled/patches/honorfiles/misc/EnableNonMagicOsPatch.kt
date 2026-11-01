package org.ungoogled.patches.honorfiles.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import org.ungoogled.patches.honor.followMagicDayNight
import org.ungoogled.patches.honor.replaceMagicFrameworkLiterals
import org.ungoogled.patches.honorfiles.HonorFilesConstants.COMPATIBILITY_HONOR_FILES
import org.ungoogled.patches.samsung.redirectCalls

internal const val HONOR_FILES_EXTENSION = "extensions/honorfiles.mpe"

private val log = java.util.logging.Logger.getLogger("HonorFiles")

/** Files' screens follow dark mode through MagicOS's theme (see followMagicDayNight). */
private val magicResourcesPatch = resourcePatch(
    description = "Gives Honor Files the day/night theme MagicOS would.",
) {
    execute {
        val (activities, styles) = followMagicDayNight()
        log.info("Day/night themes: $activities activities, $styles styles")
        // Measured against 10.0.0.641: 55 activities (the application's Theme.Magic, the sheets' translucent one).
        if (activities < 40) throw PatchException("themes changed: $activities activities, $styles styles")
    }
}

/**
 * Files' code names MagicOS framework resources by id (Theme.Magic for its dialogs and wrapped contexts, a
 * fragment animation set, the split-screen menu's "more" button): each gets the app's own copy or Android's nearest one.
 */
private val magicLiteralsPatch = bytecodePatch(
    description = "Answers MagicOS resource ids in Honor Files' code.",
) {
    dependsOn(magicResourcesPatch)
    extendWith(HONOR_FILES_EXTENSION)

    execute {
        fun id(type: ResourceType, name: String) = resourceId(type, name).toInt()
        val answers = mapOf(
            0x0202002d to id(ResourceType.STRING, "recent_more"), // string/more_menu ("More")
            0x02030029 to id(ResourceType.DRAWABLE, "ic_more24_svg"), // drawable/ic_public_more
            0x0205001c to 0x01060003, // color/icon_magic -> android:color/primary_text_light
            0x0205001d to 0x01060001, // color/icon_magic_dark -> android:color/primary_text_dark
            0x02050122 to 0x01060003, // color/icon_emphasize
            0x0205019b to id(ResourceType.COLOR, "magic_functional_red"), // color/magic_functional_red
            0x02060008 to 0x010302e3, // style/Theme.Magic -> android:style/Theme.DeviceDefault.DayNight
            0x020601ae to 0, // style/Theme.Magic.Dialog.Alert.Positive -> the activity's own alert dialog theme
            0x02080004 to id(ResourceType.ANIM, "hwrecyclerview_cubic_bezier_interpolator_type_33_33"),
            0x020a0000 to 0x010a0000, // anim/fragment_open_right_in -> android:anim/fade_in
            0x020a0001 to 0x010a0001, // anim/fragment_open_left_out -> android:anim/fade_out
            0x020a0002 to 0x010a0000, // anim/fragment_close_left_in
            0x020a0003 to 0x010a0001, // anim/fragment_close_right_out
            0x02100048 to 0x0102001b, // id/action_menu_more_button -> android:id/button3
        )
        val (replaced, moved) = replaceMagicFrameworkLiterals(answers, "Lorg/ungoogled/honorfiles/Magic;")
        log.info("MagicOS ids in code: $replaced, resolveAttribute calls: $moved")
        // Measured against 10.0.0.641: 18 literals of 14 ids.
        if (replaced.size < 14) throw PatchException("only ${replaced.size} MagicOS ids found in code")
    }
}

/** Files' ContentResolver calls that reach a provider or watch one. */
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

/**
 * Files reads, writes and watches providers of MagicOS's system services (file index, labels, cloud sync, ...); off
 * MagicOS watching one threw SecurityException at start ("Failed to find provider com.hihonor.fileservice.labelprocess").
 * Its ContentResolver calls go through Resolvers, which answers an absent Honor provider as an empty one.
 */
private val providersPatch = bytecodePatch(
    description = "Answers MagicOS's absent system providers as empty ones.",
) {
    extendWith(HONOR_FILES_EXTENSION)

    execute {
        val moved = redirectCalls("Lorg/ungoogled/honorfiles/Resolvers;", RESOLVER_CALLS)
        log.info("ContentResolver calls: $moved")
        if (moved < 50) throw PatchException("only $moved ContentResolver calls found")
    }
}

/**
 * Files keeps its screens out of the status and navigation bars with Window.setDecorFitsSystemWindows(true), which
 * Android 15 ignores for an app built for it (edge to edge): the title bars sat under the status bar. Those calls go
 * through Magic, which pads the content as fitting did.
 */
private val decorFitsPatch = bytecodePatch(
    description = "Gives Honor Files' screens MagicOS's window layout and theme.",
) {
    extendWith(HONOR_FILES_EXTENSION)

    execute {
        val moved = redirectCalls(
            "Lorg/ungoogled/honorfiles/Magic;",
            mapOf("Landroid/view/Window;" to setOf("setDecorFitsSystemWindows(Z)V")),
        )
        log.info("setDecorFitsSystemWindows calls: $moved")
        if (moved < 1) throw PatchException("setDecorFitsSystemWindows not found")
        // The home screen sets MagicOS's Theme.Magic by name (it has an action bar, which the screen needs to build its
        // UI: without one it stayed blank); Magic.getIdentifier answers with the app's MagicDayNight.
        val lookups = redirectCalls(
            "Lorg/ungoogled/honorfiles/Magic;",
            mapOf("Landroid/content/res/Resources;" to setOf("getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I")),
        )
        log.info("getIdentifier calls: $lookups")
    }
}

/** StorageInvoke.getPath(StorageVolume): Files' storage helper, by its log text. */
private object StorageInvokeFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/os/storage/StorageVolume;"),
    strings = listOf("illegal access exception when getPath"),
)

/**
 * Files' storage helper reaches the volumes through Android's hidden StorageManager / StorageVolume methods, which
 * Android refuses an app: every volume had an empty path, "Phone" listed nothing. Its methods now answer from
 * Android's public API (Storage).
 */
private val storagePatch = bytecodePatch(
    description = "Lists the phone's storage through Android's public API.",
) {
    extendWith(HONOR_FILES_EXTENSION)

    execute {
        val helper = mutableClassDefBy(StorageInvokeFingerprint.classDef.type)
        val storage = "Lorg/ungoogled/honorfiles/Storage;"
        val answers = mapOf(
            listOf("Landroid/os/storage/StorageVolume;") to "Ljava/lang/String;" to
                "invoke-static { p0 }, $storage->path(Landroid/os/storage/StorageVolume;)Ljava/lang/String;\nmove-result-object v0\nreturn-object v0",
            listOf("Landroid/os/storage/StorageManager;") to "[Landroid/os/storage/StorageVolume;" to
                "invoke-static { p0 }, $storage->volumes(Landroid/os/storage/StorageManager;)[Landroid/os/storage/StorageVolume;\nmove-result-object v0\nreturn-object v0",
            listOf("Landroid/os/storage/StorageManager;", "I") to "Ljava/lang/String;" to
                "invoke-static { p0, p1 }, $storage->userPath(Landroid/os/storage/StorageManager;I)Ljava/lang/String;\nmove-result-object v0\nreturn-object v0",
            listOf("Landroid/os/storage/StorageManager;", "Ljava/lang/String;") to "Ljava/lang/String;" to
                "invoke-static { p0, p1 }, $storage->state(Landroid/os/storage/StorageManager;Ljava/lang/String;)Ljava/lang/String;\nmove-result-object v0\nreturn-object v0",
        )
        var replaced = 0
        for ((shape, body) in answers) {
            val (params, returns) = shape
            val method = helper.methods.singleOrNull {
                it.returnType == returns && it.parameterTypes.map { p -> p.toString() } == params && it.implementation != null
            } ?: throw PatchException("storage helper method ${params.joinToString("")}$returns not found")
            if (method.implementation!!.registerCount - params.size < 1) throw PatchException("no local register in ${method.name}")
            method.addInstructions(0, body)
            replaced++
        }
        log.info("Storage helper methods: $replaced")
    }
}

/**
 * Off MagicOS Files dies at once on com.hihonor.android.view.WindowManagerEx$LayoutParamsEx (a MagicOS framework class),
 * then on MagicOS's absent system providers, stays blank, draws under the system bars and lists no storage. The
 * extension carries stand-ins for the 59 MagicOS classes it uses (generated by .github/scripts/honor-standins; the
 * Safe's key store is runtime/MagicAssets, Android-keystore encrypted), Resolvers and Storage. On an Honor phone the
 * framework's own classes load first. MagicOS's file index, labels ("Tags"), Honor cloud sync and network storage stay
 * off.
 */
@Suppress("unused")
val enableNonMagicOsPatch = bytecodePatch(
    name = "Enable non-MagicOS ROM support",
    description = "Makes Honor Files usable on non-Honor ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_HONOR_FILES)
    dependsOn(magicResourcesPatch, magicLiteralsPatch, providersPatch, decorFitsPatch, storagePatch)
    extendWith(HONOR_FILES_EXTENSION)
}
