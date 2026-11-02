package org.ungoogled.patches.xiaominotes.backup

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.xiaominotes.XiaomiNotesConstants.COMPATIBILITY_XIAOMI_NOTES
import org.ungoogled.patches.xiaominotes.misc.XIAOMI_NOTES_EXTENSION
import org.w3c.dom.Element

private val log = java.util.logging.Logger.getLogger("XiaomiNotesBackupImport")

private const val IMPORT_ACTIVITY = "org.ungoogled.xiaominotes.backup.ImportBackupActivity"
private const val BACKUP_AGENT = "Lcom/miui/notes/backup/NoteBackupAgent;"
private const val BACKUP_META = "Lmiui/app/backup/BackupMeta;"
private const val PFD = "Landroid/os/ParcelFileDescriptor;"

/** Declares the import screen and puts its row under Settings > Xiaomi Cloud, after Recently deleted. */
private val importBackupResourcesPatch = resourcePatch(
    description = "Declares the MIUI backup import screen and its Settings row.",
) {
    execute {
        val packageName = document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("activity").apply {
                    setAttribute("android:name", IMPORT_ACTIVITY)
                    setAttribute("android:exported", "false")
                    setAttribute("android:excludeFromRecents", "true")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                    // An import keeps running when the phone turns.
                    setAttribute(
                        "android:configChanges",
                        "orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode",
                    )
                },
            )
            manifest.documentElement.getAttribute("package")
        }
        if (packageName.isNullOrEmpty()) throw PatchException("manifest has no package")

        document("res/xml/preferences_settings.xml").use { settings ->
            val all = settings.getElementsByTagName("*")
            var recentlyDeleted: Element? = null
            for (i in 0 until all.length) {
                val e = all.item(i) as Element
                if (e.getAttribute("android:key") == "pref_recently_deleted") recentlyDeleted = e
            }
            recentlyDeleted ?: throw PatchException("Settings has no Recently deleted row (pref_recently_deleted)")
            val row = settings.createElement("Preference").apply {
                setAttribute("android:key", "pref_ungoogled_miui_backup_import")
                setAttribute("android:title", "Import MIUI backup")
                setAttribute("android:summary", "Notes from a Xiaomi phone's local backup (.bak)")
                setAttribute("android:persistent", "false")
                // androidx.preference opens a row's <intent> when it is tapped.
                appendChild(
                    settings.createElement("intent").apply {
                        setAttribute("android:targetPackage", packageName)
                        setAttribute("android:targetClass", IMPORT_ACTIVITY)
                    },
                )
            }
            recentlyDeleted.parentNode.insertBefore(row, recentlyDeleted.nextSibling)
        }
    }
}

/**
 * MIUI's Backup & restore writes Notes' local backup as "Notes(com.miui.notes).bak": a MIUI header, an Android backup
 * header and a tar holding Notes' backup record (miui_bak/_tmp_bak) and attached files (miui_att/...). Off a Xiaomi
 * phone nothing can restore it. The import (extension, org.ungoogled.xiaominotes.backup) reads that file or a zip of the
 * backup folder and hands the record and files to Notes' own NoteBackupAgent.onDataRestore / onAttachRestore, which add
 * notes, folders, checklists, images and tasks with their original dates without clearing anything. A second import of
 * the same backup adds nothing. Encrypted backups (locked to the Xiaomi account) are refused with an explanation.
 */
@Suppress("unused")
val importMiuiBackupPatch = bytecodePatch(
    name = "Import MIUI backup",
    description = "Adds Import MIUI backup to Settings, for notes from a Xiaomi phone's local backup.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_XIAOMI_NOTES)
    dependsOn(importBackupResourcesPatch)
    extendWith(XIAOMI_NOTES_EXTENSION)

    execute {
        // The import calls these by name; stop here if this Notes build has changed them.
        val agent = try {
            classDefBy(BACKUP_AGENT)
        } catch (e: Exception) {
            throw PatchException("NoteBackupAgent not found")
        }
        val wanted = mapOf(
            "onDataRestore" to listOf(BACKUP_META, PFD),
            "onAttachRestore" to listOf(BACKUP_META, PFD, "Ljava/lang/String;"),
            "onRestoreEnd" to listOf(BACKUP_META),
        )
        for ((name, parameters) in wanted) {
            agent.methods.firstOrNull { m ->
                m.name == name && m.returnType == "I" && m.parameterTypes.map { it.toString() } == parameters
            } ?: throw PatchException("NoteBackupAgent.$name(${parameters.joinToString("")}) not found")
        }
        log.info("NoteBackupAgent restore methods found")
    }
}
