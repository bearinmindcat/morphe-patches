package org.ungoogled.patches.samsungmyfiles.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsung.rewriteStringLiterals
import org.ungoogled.patches.samsungmyfiles.SamsungMyFilesConstants.COMPATIBILITY_SAMSUNG_MY_FILES
import org.w3c.dom.Element

internal const val SAMSUNG_MY_FILES_EXTENSION = "extensions/samsungmyfiles.mpe"

/**
 * The Trash goes through One UI's "sectrash" provider, which nothing else has: the extension carries a stand-in (see
 * TrashProvider), registered here under Samsung's authority so My Files' own calls reach it unchanged.
 */
private val trashProviderManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val providers = manifest.getElementsByTagName("provider")
            for (i in 0 until providers.length) {
                if ((providers.item(i) as Element).getAttribute("android:authorities").split(";").contains("sectrash")) {
                    throw PatchException("the manifest already has a sectrash provider")
                }
            }
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("provider").apply {
                    setAttribute("android:name", "org.ungoogled.samsungmyfiles.TrashProvider")
                    setAttribute("android:authorities", "sectrash")
                    setAttribute("android:exported", "false")
                },
            )
        }
    }
}

/** Sends My Files' uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_MY_FILES_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungmyfiles/OneUi;", "Lorg/ungoogled/samsungmyfiles/Sem;")
        java.util.logging.Logger.getLogger("SamsungMyFiles").info("One UI members: $calls calls, $fields fields")
        // Measured against 15.2.03.2: 70 calls and 6 field accesses.
        if (calls < 30) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * After an update (and at other times) My Files registers with One UI's event delegation service, cast from
 * getSystemService("semeventdelegator") without a check: off One UI that is null and the update receiver crashed.
 */
private val systemServicesPatch = bytecodePatch(
    description = "Answers One UI's system services where the phone has none.",
) {
    extendWith(SAMSUNG_MY_FILES_EXTENSION)

    execute {
        val moved = redirectSystemServices("Lorg/ungoogled/samsungmyfiles/Services;")
        // Measured against 15.2.03.2: 173 calls.
        if (moved == 0) throw PatchException("no getSystemService calls found")
    }
}

/**
 * My Files sorts names with UNICODE_NATURAL, a collation only One UI's SQLite has; elsewhere every such query fails
 * ("no such collation sequence") and folders and categories fall back to unsorted, uncached listings. NOCASE is
 * SQLite's own.
 */
private val collationPatch = bytecodePatch(
    description = "Sorts names with SQLite's own collation.",
) {
    execute {
        val rewritten = rewriteStringLiterals { s ->
            if (s.contains("COLLATE UNICODE_NATURAL")) s.replace("COLLATE UNICODE_NATURAL", "COLLATE NOCASE") else null
        }
        // Measured against 15.2.03.2: 6 literals.
        if (rewritten == 0) throw PatchException("no UNICODE_NATURAL collation found")
    }
}

/**
 * Manage storage (and the "storage full" error's button) open One UI Settings' storage analysis by an action only
 * Samsung's Settings handles: off One UI the tap crashed My Files (ActivityNotFoundException). Android's own Storage
 * settings are the same page on other ROMs.
 */
private val manageStoragePatch = bytecodePatch(
    description = "Opens Android's storage settings from Manage storage.",
) {
    execute {
        val rewritten = rewriteStringLiterals { s ->
            if (s == "com.sec.android.app.myfiles.RUN_STORAGE_ANALYSIS") "android.settings.INTERNAL_STORAGE_SETTINGS" else null
        }
        // Measured against 15.2.03.2: 4 literals (home item, storage-full dialog, shortcut, notification).
        if (rewritten == 0) throw PatchException("no storage analysis action found")
    }
}

/**
 * Off One UI My Files dies at once on One UI classes it calls (SemPersonaManager first, at start-up); the Trash,
 * sorting and Manage storage do not work, and One UI's members and services are missing. The extension carries
 * stand-ins for the 30 One UI classes it calls.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung My Files installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_MY_FILES)
    dependsOn(oneUiMembersPatch, systemServicesPatch, collationPatch, manageStoragePatch, trashProviderManifestPatch)
    extendWith(SAMSUNG_MY_FILES_EXTENSION)
}
