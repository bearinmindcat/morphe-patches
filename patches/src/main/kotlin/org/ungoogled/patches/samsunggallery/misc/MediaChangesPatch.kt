package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import org.w3c.dom.Element

private const val FAVORITES = "Lorg/ungoogled/samsunggallery/Favorites;"
private const val TRASH_OPS = "Lorg/ungoogled/samsunggallery/TrashOps;"
private const val RECYCLE_BIN = "Lorg/ungoogled/samsunggallery/RecycleBin;"
private const val REQUEST_ACTIVITY = "org.ungoogled.samsunggallery.MediaRequestActivity"

// Gallery's own classes for favourites and its Recycle bin, unobfuscated.
private const val TAG_EDITOR = "Lcom/samsung/android/gallery/module/tag/TagEditor;"
private const val FILE_ITEM = "Lcom/samsung/android/gallery/database/dbtype/FileItemInterface;"
private const val TRASH_PROVIDER_FACTORY = "Lcom/samsung/android/gallery/module/trash/factory/TrashProviderFactory;"
private const val TRASH_PROVIDER = "Lcom/samsung/android/gallery/module/trash/abstraction/ITrashProvider;"
private const val TRASH_HELPERS = "Lcom/samsung/android/gallery/module/trash/helper/"
private const val TRASH_ITEMS = "Lcom/samsung/android/gallery/module/trash/abstraction/"
private const val LOCAL_THUMBNAIL_LOADER = "Lcom/samsung/android/gallery/module/thumbnail/logic/LocalThumbnailLoaderImpl;"
private const val THUMBNAIL_REQUEST = "Lcom/samsung/android/gallery/module/thumbnail/type/ReqInfo;"
private const val MEDIA_PLAYER = "Lcom/samsung/android/gallery/support/library/v0/media/GedMediaPlayerCompat;"
private const val MEDIA_MIRROR = "Lorg/ungoogled/samsunggallery/MediaMirror;"
private const val MEDIA_MOVES = "Lorg/ungoogled/samsunggallery/MediaMoves;"
private const val FILE_OPERATION_SERVICE = "Lcom/samsung/android/gallery/app/service/FileOperationService;"
private const val PROGRESS_SERVICE = "Lcom/samsung/android/gallery/app/service/abstraction/AbsProgressService;"
private const val FILE_EDITOR = "Lcom/samsung/android/gallery/module/fileio/redact/FileEditor;"
private const val LOCAL_DB_OPERATION = "Lcom/samsung/android/gallery/module/fileio/database/mpq/MpQLocalDbOperation;"
private const val FILE_INFO = "Lcom/samsung/android/gallery/module/fileio/type/FileInfo;"

/**
 * MANAGE_MEDIA lets the user allow Gallery to make the changes below without Android's dialog each time ("Media
 * management apps"); the activity shows Android's dialog over Gallery.
 */
private val mediaChangesManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            manifest.documentElement.insertBefore(
                manifest.createElement("uses-permission").apply {
                    setAttribute("android:name", "android.permission.MANAGE_MEDIA")
                },
                application,
            )
            application.appendChild(
                manifest.createElement("activity").apply {
                    setAttribute("android:name", REQUEST_ACTIVITY)
                    setAttribute("android:exported", "false")
                    setAttribute("android:excludeFromRecents", "true")
                    setAttribute("android:configChanges", "orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                },
            )
        }
    }
}

private fun BytecodePatchContext.method(type: String, name: String, parameters: List<String>): MutableMethod =
    (mutableClassDefByOrNull(type) ?: throw PatchException("Samsung Gallery has no $type"))
        .methods.firstOrNull { it.name == name && it.parameterTypes == parameters }
        ?: throw PatchException("Samsung Gallery has no $type$name(${parameters.joinToString("")})")

/** Local registers free at the start of the method (the parameters sit in the last registers). */
private fun MutableMethod.requireLocal(count: Int = 1) {
    val parameters = parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    if (implementation!!.registerCount - parameters < count) throw PatchException("$definingClass$name has no $count local registers")
}

/**
 * Off One UI Gallery's favourites and deletes changed nothing: it writes favourites and deletes rows as the system app
 * it is on One UI, and Android lets any other app change only its own files (MediaProvider answers "0 rows"); its
 * Recycle bin, a hidden folder of its own, is One UI only. Both now go through Android's MediaStore requests (see the
 * extension's MediaRequests): favourites to MediaStore's IS_FAVORITE, deletes to Android's trash, which Gallery's
 * Recycle bin now shows (RecycleBin), and from where restore and delete permanently work. Moving another app's photos
 * to an album (a MediaStore folder change) or renaming them failed the same way; both now ask for write access first
 * (MediaMoves).
 */
internal val mediaChangesPatch = bytecodePatch(
    description = "Makes favourites, deletes and moves work through Android's MediaStore.",
) {
    dependsOn(mediaChangesManifestPatch)
    extendWith(SAMSUNG_GALLERY_EXTENSION)

    execute {
        // Gallery offers its Recycle bin (menu, setting, move-to-bin deletes) only on One UI; it is Android's trash
        // now, which every phone has.
        mutableClassDefBy(SupportTrashFeatureFingerprint.classDef.type).methods.firstOrNull {
            it.name == "getEnabling" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }?.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        ) ?: throw PatchException("Gallery's SUPPORT_TRASH feature has no getEnabling()")

        // Favourites: TagEditor writes is_favorite to Samsung's provider (or to MediaStore, which ignores it for
        // other apps' files).
        listOf("setFavorite", "setFavoriteToSecMp").forEach { name ->
            method(TAG_EDITOR, name, listOf(FILE_ITEM, "Z")).apply {
                requireLocal()
                addInstructions(
                    0,
                    """
                        invoke-static/range { p1 .. p2 }, $FAVORITES->set(Ljava/lang/Object;Z)I
                        move-result v0
                        return v0
                    """,
                )
            }
        }
        method(TAG_EDITOR, "addFavoriteInBulk", listOf("Ljava/util/List;")).apply {
            requireLocal()
            addInstructions(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $FAVORITES->add(Ljava/util/List;)I
                    move-result v0
                    return v0
                """,
            )
        }
        method(TAG_EDITOR, "removeFavoriteInBulk", listOf("Ljava/util/List;")).addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $FAVORITES->remove(Ljava/util/List;)V
                return-void
            """,
        )

        // The Recycle bin's contents: Android's trashed photos and videos instead of Gallery's own trash table.
        method(TRASH_PROVIDER_FACTORY, "getInstance", listOf("Landroid/content/Context;")).apply {
            if (returnType != TRASH_PROVIDER) throw PatchException("TrashProviderFactory.getInstance returns $returnType")
            requireLocal()
            addInstructions(
                0,
                """
                    invoke-static/range { p0 .. p0 }, $RECYCLE_BIN->provider(Landroid/content/Context;)Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, $TRASH_PROVIDER
                    return-object v0
                """,
            )
        }

        // A trashed item is shown from a still preview (see RecycleBin), also for a video: decoded as an image.
        method(LOCAL_THUMBNAIL_LOADER, "getThumbnail", listOf(THUMBNAIL_REQUEST)).apply {
            requireLocal()
            if (implementation!!.registerCount > 16) throw PatchException("thumbnail loader uses too many registers")
            addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p1, $THUMBNAIL_REQUEST->path:Ljava/lang/String;
                    invoke-static { v0 }, $RECYCLE_BIN->isPreview(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :gallery
                    invoke-virtual { p0, p1 }, $LOCAL_THUMBNAIL_LOADER->getImageThumbnail($THUMBNAIL_REQUEST)Landroid/graphics/Bitmap;
                    move-result-object v0
                    return-object v0
                """,
                ExternalLabel("gallery", implementation!!.instructions.first()),
            )
        }

        // ... and a trashed video plays from its content URI (its path is not Gallery's to read).
        method(MEDIA_PLAYER, "setDataSource", listOf("Ljava/lang/String;")).apply {
            requireLocal(2)
            if (implementation!!.registerCount > 16) throw PatchException("media player uses too many registers")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $RECYCLE_BIN->videoUri(Ljava/lang/String;)Landroid/net/Uri;
                    move-result-object v0
                    if-eqz v0, :gallery
                    invoke-static { }, $MEDIA_MIRROR->context()Landroid/content/Context;
                    move-result-object v1
                    invoke-virtual { p0, v1, v0 }, $MEDIA_PLAYER->setDataSource(Landroid/content/Context;Landroid/net/Uri;)V
                    return-void
                """,
                ExternalLabel("gallery", implementation!!.instructions.first()),
            )
        }

        // Move to the bin (or delete), restore, delete permanently: each helper's per-item step queues the item, its
        // done() makes one request for all of them. An item the extension cannot place keeps Gallery's own steps.
        listOf(
            Triple("TrashDeleteHelper", "deleteItemInternal", listOf("${TRASH_ITEMS}TrashDeleteItem;", "Z")) to "delete",
            Triple("TrashRestoreHelper", "restoreItemInternal", listOf("${TRASH_ITEMS}TrashRestoreItem;")) to "restore",
            Triple("TrashEmptyHelper", "emptyItemInternal", listOf("${TRASH_ITEMS}TrashEmptyItem;")) to "empty",
        ).forEach { (target, op) ->
            val (helper, name, parameters) = target
            val type = "$TRASH_HELPERS$helper;"
            method(type, name, parameters).apply {
                requireLocal()
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static/range { p0 .. p1 }, $TRASH_OPS->$op(Ljava/lang/Object;Ljava/lang/Object;)Z
                        move-result v0
                        if-eqz v0, :gallery
                        return-void
                    """,
                    ExternalLabel("gallery", implementation!!.instructions.first()),
                )
            }
            method(type, "done", emptyList()).addInstructions(
                0,
                "invoke-static/range { p0 .. p0 }, $TRASH_OPS->flush(Ljava/lang/Object;)V",
            )
        }
        // Move to album and album rename: one write request for the queued items as the operation starts, on the
        // service's own thread and before its first job, so Gallery's MediaStore folder changes are allowed.
        if (method(PROGRESS_SERVICE, "getUnFinishedJob", emptyList()).returnType != "Ljava/util/Queue;") {
            throw PatchException("Gallery's AbsProgressService.getUnFinishedJob() does not answer a Queue")
        }
        method(FILE_OPERATION_SERVICE, "onStartInternal", emptyList()).apply {
            if (mutableClassDefBy(definingClass).fields.none { it.name == "mOperationType" && it.type == "Ljava/lang/String;" }) {
                throw PatchException("Gallery's FileOperationService has no mOperationType")
            }
            requireLocal(2)
            if (implementation!!.registerCount > 16) throw PatchException("FileOperationService.onStartInternal() has too many registers")
            addInstructions(
                0,
                """
                    invoke-virtual { p0 }, $PROGRESS_SERVICE->getUnFinishedJob()Ljava/util/Queue;
                    move-result-object v0
                    iget-object v1, p0, $FILE_OPERATION_SERVICE->mOperationType:Ljava/lang/String;
                    invoke-static { v1, v0 }, $MEDIA_MOVES->beforeStart(Ljava/lang/String;Ljava/util/Collection;)V
                """,
            )
        }

        // A move to another storage (or one replacing an item) copies, then removes the original by a files uri, which
        // the write access does not cover.
        method(LOCAL_DB_OPERATION, "updateDatabaseByCopyInsteadOfMove", listOf("Landroid/content/Context;", FILE_INFO))
            .addInstructions(0, "invoke-static/range { p2 .. p2 }, $MEDIA_MOVES->removeSource(Ljava/lang/Object;)V")

        // The details screen's rename, date and location changes (and the timeline's bulk ones) go through FileEditor:
        // the same write request for its items, on its file thread, before the first.
        method(FILE_EDITOR, "handleFile", emptyList()).apply {
            if (mutableClassDefBy(definingClass).fields.none { it.name == "mFiles" && it.type == "[$FILE_ITEM" }) {
                throw PatchException("Gallery's FileEditor has no mFiles")
            }
            requireLocal()
            if (implementation!!.registerCount > 16) throw PatchException("FileEditor.handleFile() has too many registers")
            addInstructions(
                0,
                """
                    iget-object v0, p0, $FILE_EDITOR->mFiles:[$FILE_ITEM
                    invoke-static { v0 }, $MEDIA_MOVES->beforeEdit([Ljava/lang/Object;)V
                """,
            )
        }

        // Delete permanently from the bin's viewer ends with dump(), not done().
        method("${TRASH_HELPERS}TrashHelper;", "dump", listOf("${TRASH_ITEMS}TrashLogType;", "Ljava/lang/String;")).addInstructions(
            0,
            "invoke-static/range { p0 .. p0 }, $TRASH_OPS->flush(Ljava/lang/Object;)V",
        )
    }
}
