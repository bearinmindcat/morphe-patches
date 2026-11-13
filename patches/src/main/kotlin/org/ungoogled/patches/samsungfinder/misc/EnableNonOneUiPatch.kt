package org.ungoogled.patches.samsungfinder.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsungfinder.SamsungFinderConstants.COMPATIBILITY_SAMSUNG_FINDER
import org.w3c.dom.Element

internal const val SAMSUNG_FINDER_EXTENSION = "extensions/samsungfinder.mpe"

private const val FINDER = "com.samsung.android.app.galaxyfinder"
private const val DEVICE_SEARCH = "com.samsung.android.intent.action.DEVICE_SEARCH"

/** Sends Finder's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_FINDER_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungfinder/OneUi;", "Lorg/ungoogled/samsungfinder/Sem;")
        java.util.logging.Logger.getLogger("SamsungFinder").info("One UI members: $calls calls, $fields fields")
        // Measured against 10.2.13.0: 64 calls and 6 field accesses (One UI's searchables among them, see Insight).
        if (calls < 30) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * The launcher icon: One UI opens Finder from the home screen's search, so its launcher entry ships turned off.
 * Settings and files: One UI's Settings and My Files answer Finder through Samsung's search provider contract; the
 * extension's SettingsSource and FilesSource answer it the same way here, from Android's public settings pages and
 * media store (all files with "All files access", which Files asks for in its results).
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val activities = manifest.getElementsByTagName("activity")
            val launcher = (0 until activities.length).map { activities.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == "$FINDER.GalaxyFinderMainLauncher" }
                ?: throw PatchException("Finder's launcher entry was not found")
            launcher.setAttribute("android:enabled", "true")

            val root = manifest.documentElement
            val permissions = manifest.getElementsByTagName("uses-permission")
            val declared = (0 until permissions.length).map { (permissions.item(it) as Element).getAttribute("android:name") }
            val application = manifest.getElementsByTagName("application").item(0) as Element
            if ("android.permission.MANAGE_EXTERNAL_STORAGE" !in declared) {
                root.insertBefore(
                    manifest.createElement("uses-permission").apply {
                        setAttribute("android:name", "android.permission.MANAGE_EXTERNAL_STORAGE")
                    },
                    application,
                )
            }
            for ((source, authority) in listOf("SettingsSource" to "settings", "FilesSource" to "files")) {
                application.appendChild(
                    manifest.createElement("provider").apply {
                        setAttribute("android:name", "org.ungoogled.samsungfinder.$source")
                        setAttribute("android:authorities", "$FINDER.ungoogled.$authority")
                        setAttribute("android:exported", "false")
                        appendChild(
                            manifest.createElement("intent-filter").apply {
                                appendChild(manifest.createElement("action").apply { setAttribute("android:name", DEVICE_SEARCH) })
                            },
                        )
                    },
                )
            }
        }
    }
}

/** SearchableDataApp.toString: its class is one search source found through Samsung's provider contract. */
private object SearchableDataAppFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("SearchableDataApp{authority='"),
)

/**
 * Finder keys each search source by its app's package; the two sources above both live in Finder, so each gets a key
 * of its own (see Sources).
 */
private val sourceKeysPatch = bytecodePatch(
    description = "Keeps Finder's own search sources apart.",
) {
    extendWith(SAMSUNG_FINDER_EXTENSION)

    execute {
        val toString = SearchableDataAppFingerprint.methodOrNull ?: throw PatchException("SearchableDataApp was not found")
        val constructor = mutableClassDefBy(toString.definingClass).methods.firstOrNull {
            it.name == "<init>" && it.parameterTypes.map { p -> p.toString() } == listOf(
                "Landroid/content/Context;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;",
                "Landroid/graphics/drawable/Icon;", "Landroid/content/Intent;", "Landroid/content/Intent;",
                "Landroid/content/ComponentName;",
            )
        } ?: throw PatchException("SearchableDataApp's constructor was not found")
        // (context, package, authority, ...): the package becomes the source's key.
        constructor.addInstructions(
            0,
            """
                invoke-static { p2, p3 }, Lorg/ungoogled/samsungfinder/Sources;->key(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object p2
            """,
        )
    }
}

private const val DATA_POLICY = "Lcom/samsung/android/app/galaxyfinder/search/manager/DataPolicyManager;"

/**
 * One UI's Settings and Contacts answer as you type, right after apps; every other source only on "Show more". The
 * Settings and Files sources here and a contacts app's own provider take those places (see Sources.policy).
 */
private val sourcePolicyPatch = bytecodePatch(
    description = "Shows settings, files and contacts as you type.",
) {
    extendWith(SAMSUNG_FINDER_EXTENSION)

    execute {
        if (classDefByOrNull(DATA_POLICY) == null) throw PatchException("Finder's source policy was not found")
        // Its two lookups by source key: the place (I) and whether the source answers as you type (Z).
        val lookups = mutableClassDefBy(DATA_POLICY).methods.filter {
            it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/String;") &&
                (it.returnType == "I" || it.returnType == "Z") && it.implementation != null
        }
        // Measured against 10.2.13.0: the place (I) and the as-you-type flag (Z).
        if (lookups.size != 2) throw PatchException("Finder's source policy lookups were not found (${lookups.size})")
        lookups.forEach {
            it.addInstructions(
                0,
                """
                    invoke-static { p1 }, Lorg/ungoogled/samsungfinder/Sources;->policy(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """,
            )
        }
    }
}

private const val DIRECTORIES = "Lorg/ungoogled/samsungfinder/Directories;"

/** The Downloads shortcut's tap: My Files' "view category" action. */
private object DownloadsShortcutFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    strings = listOf("com.sec.android.app.myfiles.VIEW_CATEGORY"),
)

/** The Screenshots shortcut's target: Samsung Gallery's screenshots album. */
private object ScreenshotsShortcutFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
    strings = listOf("com.android.gallery.action.SHORTCUT_ALBUM_VIEW"),
)

/** The two shortcuts' icon: an app's activity icon by package and class. */
private object ShortcutIconFingerprint : Fingerprint(
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
    custom = { method, classDef ->
        classDef.methods.any { m -> m.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "SuggestedDirectoryWorker"
        } == true }
    },
)

/**
 * Finder's Downloads and Screenshots shortcuts open Samsung My Files and Samsung Gallery: without them the taps did
 * nothing and the icons were Android's placeholder. They open the Files app instead (see Directories).
 */
private val directoryShortcutsPatch = bytecodePatch(
    description = "Opens Downloads and Screenshots in the Files app without Samsung's apps.",
) {
    extendWith(SAMSUNG_FINDER_EXTENSION)

    execute {
        val downloads = DownloadsShortcutFingerprint.methodOrNull ?: throw PatchException("the Downloads shortcut was not found")
        val start = downloads.implementation!!.instructions.indexOfFirst {
            val r = (it as? ReferenceInstruction)?.reference as? MethodReference
            it.opcode == Opcode.INVOKE_VIRTUAL && r?.name == "startActivity" &&
                r.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Intent;")
        }
        if (start < 0) throw PatchException("the Downloads shortcut's start was not found")
        val call = downloads.implementation!!.instructions[start] as FiveRegisterInstruction
        downloads.replaceInstruction(
            start,
            "invoke-static { v${call.registerC}, v${call.registerD} }, $DIRECTORIES->start(Landroid/content/Context;Landroid/content/Intent;)V",
        )

        val screenshots = ScreenshotsShortcutFingerprint.methodOrNull ?: throw PatchException("the Screenshots shortcut was not found")
        screenshots.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, $DIRECTORIES->screenshots(Landroid/content/Context;)Z
                move-result v0
                if-eqz v0, :samsung_gallery
                return-void
            """,
            ExternalLabel("samsung_gallery", screenshots.implementation!!.instructions.first()),
        )

        val icon = ShortcutIconFingerprint.methodOrNull ?: throw PatchException("the shortcuts' icon was not found")
        if (icon.implementation!!.registerCount < 4) throw PatchException("no spare register in the shortcuts' icon")
        icon.addInstructions(
            0,
            """
                invoke-static { p1, p2 }, $DIRECTORIES->iconClass(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                invoke-static { p1, p2 }, $DIRECTORIES->iconPackage(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
                move-object p2, v0
            """,
        )
    }
}

/** Finder settings' "Show Finder on Apps screen" switch, set up by its key. */
private object AppsScreenSwitchFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("key_add_finder_shortcut"),
)

/**
 * "Show Finder on Apps screen" turns Finder's launcher icon off; on One UI the home screen still opens Finder, but
 * elsewhere that icon is the only way in, so turning it off locked Finder away. The switch is hidden.
 */
private val appsScreenSwitchPatch = bytecodePatch(
    description = "Hides the switch that removes Finder's only icon.",
) {
    extendWith(SAMSUNG_FINDER_EXTENSION)

    execute {
        val setup = AppsScreenSwitchFingerprint.methodOrNull ?: throw PatchException("the Apps screen switch was not found")
        val cast = setup.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.CHECK_CAST &&
                ((it as ReferenceInstruction).reference as TypeReference).type == "Landroidx/preference/SwitchPreferenceCompat;"
        }
        if (cast < 0) throw PatchException("the Apps screen switch's preference was not found")
        val register = (setup.implementation!!.instructions[cast] as OneRegisterInstruction).registerA
        setup.addInstruction(cast + 1, "invoke-static { v$register }, Lorg/ungoogled/samsungfinder/Access;->hideIconSwitch(Ljava/lang/Object;)V")
    }
}

/** GalaxyFinderActivity.onResume. */
private object SearchScreenResumeFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("onResume: w="),
    custom = { method, _ -> method.name == "onResume" },
)

/** On One UI Finder may read contacts as a system app; here the search screen asks once (see Access). */
private val contactsAccessPatch = bytecodePatch(
    description = "Asks once for contacts access, to search contacts.",
) {
    extendWith(SAMSUNG_FINDER_EXTENSION)

    execute {
        val resume = SearchScreenResumeFingerprint.methodOrNull ?: throw PatchException("the search screen was not found")
        resume.addInstructions(0, "invoke-static { p0 }, Lorg/ungoogled/samsungfinder/Access;->ask(Landroid/app/Activity;)V")
    }
}

private const val SOURCE_LIST = "Lcom/samsung/android/app/galaxyfinder/search/searchable/manager/SearchableDataListManager;"

/** SearchableDataListManager's last pass over the sources it found (it drops some messaging apps there). */
private object SourceListFilterFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Ljava/util/List;"),
    custom = { method, _ -> method.definingClass == SOURCE_LIST },
)

/**
 * YouTube, Spotify, Melon, Galaxy Store and Branch results are fetched by Finder itself from those services' web
 * APIs with Samsung's keys, on every search, with nothing ever agreed to: those sources are left out (see Sources).
 * Apps' own search providers (contacts, notes, Play Store, ...) stay; "Search with Google" and opening an app's own
 * search stay, as the user's own taps.
 */
private val onlineSourcesPatch = bytecodePatch(
    description = "Leaves out the sources Finder fetches from the network.",
) {
    extendWith(SAMSUNG_FINDER_EXTENSION)

    execute {
        val filter = SourceListFilterFingerprint.methodOrNull ?: throw PatchException("Finder's source list was not found")
        filter.addInstructions(0, "invoke-static { p2 }, Lorg/ungoogled/samsungfinder/Sources;->dropOnline(Ljava/util/List;)V")
    }
}

/** CheckForUpdateTask.doInBackground: asks Galaxy Store's server for a newer Finder. */
private object UpdateCheckFingerprint : Fingerprint(
    returnType = "Ljava/lang/Integer;",
    strings = listOf("GalaxyApps is not installed on this device"),
)

/**
 * Where Galaxy Store is installed Finder asks Samsung's server for updates on its own; it now answers "Galaxy Store not
 * installed" (99), as it does on most phones, so Samsung hears nothing.
 */
private val updateCheckPatch = bytecodePatch(
    description = "Stops Finder's own update check with Samsung's server.",
) {
    execute {
        val check = UpdateCheckFingerprint.methodOrNull ?: throw PatchException("Finder's update check was not found")
        check.addInstructions(
            0,
            """
                const/16 v0, 0x63
                invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v0
                return-object v0
            """,
        )
    }
}

/**
 * Off One UI Finder dies on One UI classes and members it calls, finds nothing to search in (the list comes from One
 * UI's search service), has no launcher icon and no settings or file results. The extension carries stand-ins for the
 * One UI classes it calls.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Finder installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_FINDER)
    dependsOn(
        oneUiMembersPatch, manifestPatch, sourceKeysPatch, contactsAccessPatch, onlineSourcesPatch, updateCheckPatch, sourcePolicyPatch,
        directoryShortcutsPatch, appsScreenSwitchPatch,
    )
    extendWith(SAMSUNG_FINDER_EXTENSION)
}
