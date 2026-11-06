package org.ungoogled.patches.samsungnotes.transfer

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsungnotes.SamsungNotesConstants.COMPATIBILITY_SAMSUNG_NOTES
import org.ungoogled.patches.samsungnotes.misc.SAMSUNG_NOTES_EXTENSION
import org.w3c.dom.Element

private const val TRANSFER_ACTIVITY = "org.ungoogled.samsungnotes.transfer.NotesTransferActivity"
private const val IMPORT_ACTION = "org.ungoogled.samsungnotes.IMPORT_NOTES"
private const val EXPORT_ACTION = "org.ungoogled.samsungnotes.EXPORT_NOTES"

/** Declares the import/export screen and puts its two rows at the end of Settings > General. */
private val importExportResourcesPatch = resourcePatch(
    description = "Declares the import and export screen and its Settings rows.",
) {
    execute {
        val packageName = document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("activity").apply {
                    setAttribute("android:name", TRANSFER_ACTIVITY)
                    setAttribute("android:exported", "false")
                    setAttribute("android:excludeFromRecents", "true")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                    // An import or export keeps running when the phone turns.
                    setAttribute(
                        "android:configChanges",
                        "orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density",
                    )
                },
            )
            manifest.documentElement.getAttribute("package")
        }
        if (packageName.isNullOrEmpty()) throw PatchException("manifest has no package")

        document("res/xml/settings_main_preference.xml").use { settings ->
            val all = settings.getElementsByTagName("*")
            var general: Element? = null
            for (i in 0 until all.length) {
                val e = all.item(i) as Element
                if (e.getAttribute("android:key") == "settings_general_category") general = e
            }
            general ?: throw PatchException("Settings has no General section (settings_general_category)")
            fun row(key: String, title: String, summary: String, action: String) =
                settings.createElement("androidx.preference.Preference").apply {
                    setAttribute("android:key", key)
                    setAttribute("android:title", title)
                    setAttribute("android:summary", summary)
                    setAttribute("android:persistent", "false")
                    // androidx.preference opens a row's <intent> when it is tapped.
                    appendChild(
                        settings.createElement("intent").apply {
                            setAttribute("android:action", action)
                            setAttribute("android:targetPackage", packageName)
                            setAttribute("android:targetClass", TRANSFER_ACTIVITY)
                        },
                    )
                }
            general.appendChild(row("settings_ungoogled_import_notes", "Import notes", "Samsung Notes files (.sdocx) or a zip of them", IMPORT_ACTION))
            general.appendChild(row("settings_ungoogled_export_notes", "Export notes", "All unlocked notes as a zip of .sdocx files", EXPORT_ACTION))
        }
    }
}

private const val NT = "Lcom/samsung/android/support/senl/nt/"

/** The Notes code the import and export call by name: class -> methods (name + parameter types). */
private val NOTES_CODE = mapOf(
    "Lcom/samsung/android/sdk/pen/worddoc/SpenWNoteFile;" to listOf(
        "isValid(Ljava/lang/String;)", "isLocked(Ljava/lang/String;)", "getBodyTextString(Ljava/lang/String;)",
        "getDisplayCreatedTime(Ljava/lang/String;)", "getDisplayModifiedTime(Ljava/lang/String;)",
    ),
    "${NT}base/common/sdk/util/SpenSdkInitializer;" to listOf("initialize(Landroid/content/Context;)"),
    "${NT}base/common/TimeManager;" to listOf("convertSdkTimeToAppTime(J)"),
    "${NT}base/common/util/UUIDUtils;" to listOf("newUUID(Landroid/content/Context;)"),
    "${NT}model/folder/FolderManager;" to listOf("getInstance()", "findAndMakeCategory(Ljava/lang/String;)"),
    "${NT}model/importer/task/param/ImportSdocXParam;" to listOf("<init>()"),
    "${NT}model/importer/task/param/ImportBaseParam;" to listOf(
        "setContext(Landroid/content/Context;)", "setUuid(Ljava/lang/String;)", "setExternalPath(Ljava/lang/String;)",
        "setFolderUuid(Ljava/lang/String;)", "setCaller(Ljava/lang/String;)",
    ),
    "${NT}model/importer/task/ImportSdocXTask;" to listOf("<init>(${NT}model/importer/task/param/ImportSdocXParam;)"),
    "${NT}model/importer/task/ImportTask;" to listOf("setImported(Z)", "call()"),
    "${NT}composer/main/base/model/share/ShareData;" to listOf(
        "<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJLjava/lang/String;)",
    ),
    "${NT}composer/main/base/presenter/share/ShareCacheHelper;" to listOf("<init>()"),
    "${NT}composer/main/base/presenter/share/MakeUriHelper;" to listOf(
        "<init>(${NT}composer/main/base/presenter/share/ShareCacheHelper;)",
        "makeSDoc(Landroid/content/Context;${NT}composer/main/base/model/share/ShareData;Ljava/lang/String;)",
        "makeText(Landroid/content/Context;Lcom/samsung/android/sdk/pen/worddoc/SpenWNote;Ljava/lang/String;)",
    ),
    "${NT}app/common/util/NotesUtils;" to listOf("getSpenWNote(Landroid/content/Context;Ljava/lang/String;Z)"),
    "${NT}model/documents/file/DocumentFileManager;" to listOf(
        "getInstance()", "close(Ljava/lang/String;Lcom/samsung/android/sdk/pen/worddoc/SpenWNote;Z)",
    ),
    "${NT}app/main/noteslist/presenter/task/ShareHandler;" to listOf(
        "<init>()",
        "makePDFFiles(Landroid/content/Context;Lcom/samsung/android/sdk/composer/pdf/SpenNotePdfExport;Ljava/util/List;" +
            "Ljava/lang/String;${NT}app/main/noteslist/presenter/task/ShareHandler\$Callback;)",
    ),
    "Lcom/samsung/android/sdk/composer/pdf/SpenNotePdfExport;" to listOf("<init>(Landroid/content/Context;I)", "close()"),
)

/**
 * Notes reads Samsung Notes files (.sdocx) only when one is shared to it, one at a time, and writes them only through
 * Share. The extension (org.ungoogled.samsungnotes.transfer) adds Import notes, which hands every .sdocx in the picked
 * files or zips to Notes' own ImportSdocXTask (new notes only, folders from the zip, duplicates and locked notes
 * skipped), and Export notes, which writes every unlocked note with Notes' own makeSDoc into one zip, optionally with
 * Notes' own PDF and text copies.
 */
@Suppress("unused")
val importExportNotesPatch = bytecodePatch(
    name = "Import and export notes",
    description = "Adds Import notes and Export notes (.sdocx files) to Settings.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_NOTES)
    dependsOn(importExportResourcesPatch)
    extendWith(SAMSUNG_NOTES_EXTENSION)

    execute {
        // The extension calls these by name; stop here if this Notes build has changed them.
        for ((type, methods) in NOTES_CODE) {
            val classDef = classDefByOrNull(type) ?: throw PatchException("${type.removeSuffix(";").substringAfterLast('/')} not found")
            val present = classDef.methods.map { m -> "${m.name}(${m.parameterTypes.joinToString("")})" }.toSet()
            for (method in methods) {
                if (method !in present) throw PatchException("${classDef.type}->$method not found")
            }
        }
        java.util.logging.Logger.getLogger("SamsungNotes").info("Import/export: ${NOTES_CODE.values.sumOf { it.size }} Notes methods found")
    }
}
