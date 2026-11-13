package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectToWrappers
import org.ungoogled.patches.samsunggallery.SamsungGalleryConstants.COMPATIBILITY_SAMSUNG_GALLERY
import org.w3c.dom.Element

internal const val SAMSUNG_GALLERY_EXTENSION = "extensions/samsunggallery.mpe"
private const val MEDIA_MIRROR = "Lorg/ungoogled/samsunggallery/MediaMirror;"
private const val MEDIA_QUERIES = "Lorg/ungoogled/samsunggallery/MediaQueries;"

/** The URIs Gallery uses off One UI (its "generic device" table), unobfuscated. */
private const val MEDIA_URI_GED = "Lcom/samsung/android/gallery/support/providers/MediaUriGed;"
/** Gallery's URIs into Samsung's media analysis; the base class (all "unsupported") is the one used off One UI. */
private const val CMH_URIS = "Lcom/samsung/android/gallery/support/providers/CmhUri\$PrivilegedUri;"
private const val PREFERENCE_FEATURES = "Lcom/samsung/android/gallery/support/utils/PreferenceFeatures;"
/** Gallery's Android-only answers to what One UI adds (used where SEM version is 0), unobfuscated. */
private const val GED_API_COMPAT = "Lcom/samsung/android/gallery/support/library/v0/GedApiCompatImpl;"
private const val ACTIVITY_STATE = "Lorg/ungoogled/samsunggallery/ActivityState;"
private const val MIRRORED_PHOTOS = "Lorg/ungoogled/samsunggallery/MirroredPhotos;"
private const val EXIF_INTERFACE = "Landroidx/exifinterface/media/ExifInterface;"
/** Gallery's disk cache for thumbnails, unobfuscated. */
private const val CACHE_MANAGER = "Lcom/samsung/android/gallery/support/cache/CacheManager;"
/** Gallery's choice of GIF decoder and its feature flags, unobfuscated. */
private const val GIF_ANIMATION_FACTORY = "Lcom/samsung/android/gallery/module/media/GifAnimationFactory;"
private const val GIF_ANIMATION = "Lcom/samsung/android/gallery/module/media/GifAnimation;"
private const val FEATURES = "Lcom/samsung/android/gallery/support/utils/Features;"
private const val ANIMATED_IMAGES = "Lorg/ungoogled/samsunggallery/AnimatedImages;"
/** The intent Gallery was started with, and its viewer of a photo within its album, unobfuscated. */
private const val LAUNCH_INTENT = "Lcom/samsung/android/gallery/module/abstraction/LaunchIntent;"
private const val LOCAL_ALBUM_LAUNCHER = "Lcom/samsung/android/gallery/app/activity/external/launcher/LocalAlbumViewLauncher;"
private const val LATCH = "Ljava/util/concurrent/CountDownLatch;"
private const val OPEN_IN_ALBUM = "Lorg/ungoogled/samsunggallery/OpenInAlbum;"

/**
 * Off One UI the libraries Samsung's system provides are missing; Gallery asks for 45, one of them required
 * (libOpenCL.so), which alone stops the install. Every one becomes optional: the features built on them stay off.
 * The media copy that answers Gallery's SQL is declared here too.
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val list = manifest.getElementsByTagName("uses-native-library")
            if (list.length == 0) throw PatchException("no uses-native-library in the manifest")
            for (i in 0 until list.length) (list.item(i) as Element).setAttribute("android:required", "false")

            val packageName = manifest.documentElement.getAttribute("package")
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("provider").apply {
                    setAttribute("android:name", "org.ungoogled.samsunggallery.MediaMirror")
                    setAttribute("android:authorities", "$packageName.mediamirror")
                    setAttribute("android:exported", "false")
                },
            )
        }
    }
}

@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Gallery installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_GALLERY)
    dependsOn(manifestPatch, oneUiMembersPatch, mediaChangesPatch, videoEditPatch)
    extendWith(SAMSUNG_GALLERY_EXTENSION)

    execute {
        // Gallery's queries are raw SQL to Samsung's MediaProvider; off One UI the generic URI table has none to
        // offer and throws. The media copy takes them.
        mutableClassDefBy(MEDIA_URI_GED).methods.single { it.name == "getRawQueryUri" }.addInstructions(
            0,
            """
                invoke-static { }, $MEDIA_MIRROR->rawQueryUri()Landroid/net/Uri;
                move-result-object v0
                return-object v0
            """,
        )

        // The user's tags ("Add tag" in a photo's details) go to Samsung's media provider (content://secmedia/cmh/
        // usertag); off One UI Gallery's URI table has "content://unsupported" for them, so every tag vanished and a
        // search by tag found nothing. The media copy keeps them (in a database of their own) and searches them.
        val privilegedUri = mutableClassDefByOrNull(CMH_URIS) ?: throw PatchException("Samsung Gallery has no $CMH_URIS")
        mapOf("getUserTags" to "userTagsUri", "getTagView" to "tagViewUri").forEach { (name, target) ->
            privilegedUri.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Landroid/net/Uri;"
            }?.addInstructions(
                0,
                """
                    invoke-static { }, $MEDIA_MIRROR->$target()Landroid/net/Uri;
                    move-result-object v0
                    return-object v0
                """,
            ) ?: throw PatchException("no $name in Gallery's generic-device URI table")
        }

        // Mirrored photos (selfies saved as previewed: EXIF orientation 2, 4, 5 or 7) showed unmirrored: Gallery mirrors
        // only by One UI's orientation_tag column, which it reads only with Samsung's media provider. The media copy
        // now has that column, read from the photos.
        mutableClassDefBy(OrientationTagFeatureFingerprint.classDef.type).methods.firstOrNull {
            it.name == "getEnabling" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }?.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        ) ?: throw PatchException("Gallery's SUPPORT_SEC_MP_ORIENTATION_TAG feature has no getEnabling()")

        // A selfie's embedded EXIF thumbnail need not be mirrored the way the photo is (GrapheneOS Camera mirrors by EXIF
        // orientation, other cameras mirror the pixels but keep an unmirrored thumbnail): the grid and the viewer's first
        // frame, made from it, showed the selfie the other way round and the viewer flipped when the photo itself was
        // decoded. Gallery gets no embedded thumbnail for photos saved mirrored or whose thumbnail does not match the
        // photo (it decodes the photo instead), and cached thumbnails of JPEGs get keys of their own.
        val exifThumbnails = redirectCalls(
            mapOf(
                "$EXIF_INTERFACE->getThumbnail()[B" to "$MIRRORED_PHOTOS->thumbnail(Ljava/lang/Object;)[B",
                "$EXIF_INTERFACE->getThumbnailBytes()[B" to "$MIRRORED_PHOTOS->thumbnailBytes(Ljava/lang/Object;)[B",
                "$EXIF_INTERFACE->hasThumbnail()Z" to "$MIRRORED_PHOTOS->hasThumbnail(Ljava/lang/Object;)Z",
            ),
        )
        // Measured against 15.9.01.7: the local and URI thumbnail loaders, the file browser and the EXIF details.
        if (exifThumbnails < 3) throw PatchException("only $exifThumbnails EXIF thumbnail reads found")
        java.util.logging.Logger.getLogger("SamsungGallery").info("EXIF thumbnail reads: $exifThumbnails")
        (mutableClassDefByOrNull(CACHE_MANAGER) ?: throw PatchException("Samsung Gallery has no $CACHE_MANAGER"))
            .methods.firstOrNull {
                it.name == "generateKey" && it.parameterTypes == listOf("Ljava/lang/String;", "J") &&
                    AccessFlags.STATIC.isSet(it.accessFlags)
            }?.addInstructions(
                0,
                """
                    invoke-static { p0 }, $MIRRORED_PHOTOS->cacheKey(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p0
                """,
            ) ?: throw PatchException("no generateKey(String, long) in Gallery's thumbnail cache")

        // Gallery also queries MediaStore itself with Samsung's columns (media_id, cloud hashes ...), which Google's
        // MediaStore refuses with an exception Gallery does not catch: the viewer crashed whenever the media changed
        // under it (finding its photo again asks for "media_id="). Every query goes through MediaQueries, which puts
        // the ones MediaStore refuses to the media copy.
        val secFileIdClass = SecFileIdFingerprint.classDef.type
        val secFileIdName = SecFileIdFingerprint.method.name
        val queries = redirectToWrappers(MEDIA_QUERIES, setOf("Landroid/content/ContentResolver;"))
        // Measured against 15.9.01.7: 135 queries.
        if (queries < 100) throw PatchException("only $queries content queries found")
        val secFileId = mutableClassDefBy(secFileIdClass).methods.first { it.name == secFileIdName && it.parameterTypes == listOf("J") }
        if (secFileId.implementation!!.instructions.none { (it as? ReferenceInstruction)?.reference?.toString()?.startsWith(MEDIA_QUERIES) == true }) {
            throw PatchException("Samsung file id lookup still queries MediaStore directly")
        }
        java.util.logging.Logger.getLogger("SamsungGallery").info("Content queries: $queries")

        // "Is the screen in front?" comes from One UI's Activity.semIsResumed; Gallery's answer for other phones is a
        // flat no, so whatever Gallery starts only from a screen in front never started: Edit did nothing ("Edit Menu
        // Select failed: paused"). Answered from the activity itself.
        mutableClassDefBy(GED_API_COMPAT).methods.firstOrNull {
            it.name == "isActivityResumed" && it.parameterTypes == listOf("Landroid/app/Activity;") && it.returnType == "Z"
        }?.addInstructions(
            0,
            """
                invoke-static { p1 }, $ACTIVITY_STATE->isResumed(Landroid/app/Activity;)Z
                move-result v0
                return v0
            """,
        ) ?: throw PatchException("no isActivityResumed in Gallery's generic-device API table")

        // GIFs played as one still frame: Gallery decodes them with Samsung's decoder (a One UI system library) unless it
        // runs on a "generic device", where it uses Android's AnimatedImageDrawable. Android's is used when Samsung's is
        // missing.
        mutableClassDefBy(GIF_ANIMATION_FACTORY).methods.firstOrNull {
            it.name == "createGifAnimation" && it.parameterTypes.isEmpty() && it.returnType == GIF_ANIMATION
        }?.let { create ->
            val instructions = create.implementation!!.instructions.toList()
            val isGed = instructions.indexOfFirst {
                (it as? ReferenceInstruction)?.reference?.toString() == "$FEATURES->isEnabled($FEATURES)Z"
            }
            val result = instructions.getOrNull(isGed + 1)
            if (isGed < 0 || result?.opcode != Opcode.MOVE_RESULT) {
                throw PatchException("Gallery's GIF decoder choice no longer asks for IS_GED")
            }
            val register = (result as OneRegisterInstruction).registerA
            create.addInstructions(
                isGed + 2,
                """
                    invoke-static { v$register }, $ANIMATED_IMAGES->useAndroidDecoder(Z)Z
                    move-result v$register
                """,
            )
        } ?: throw PatchException("no createGifAnimation() in Gallery's $GIF_ANIMATION_FACTORY")

        // A photo opened from a camera app other than Samsung's (GCam's REVIEW, or VIEW through a "Photos" stub) showed
        // alone. Gallery opens a photo within its album when the intent names the album (bucketId, as Samsung's My Files
        // does); OpenInAlbum adds it for a camera's photo before Gallery reads the intent, and makes REVIEW a VIEW.
        (mutableClassDefByOrNull(LAUNCH_INTENT) ?: throw PatchException("Samsung Gallery has no $LAUNCH_INTENT"))
            .methods.firstOrNull { it.name == "<init>" && it.parameterTypes == listOf("Landroid/content/Intent;") }
            ?.let { constructor ->
                val superCall = constructor.implementation!!.instructions.indexOfFirst {
                    it.opcode == Opcode.INVOKE_DIRECT && (it as ReferenceInstruction).reference.toString() == "Ljava/lang/Object;-><init>()V"
                }
                if (superCall < 0) throw PatchException("$LAUNCH_INTENT<init> does not call Object.<init>")
                constructor.addInstructions(
                    superCall + 1,
                    "invoke-static { p1 }, $OPEN_IN_ALBUM->prepare(Landroid/content/Intent;)V",
                )
            } ?: throw PatchException("no $LAUNCH_INTENT<init>(Intent)")

        // Gallery waits 2 s for the photo's row before opening the album, and opens nothing after that; the media copy
        // made on a cold start can take longer.
        (mutableClassDefByOrNull(LOCAL_ALBUM_LAUNCHER) ?: throw PatchException("Samsung Gallery has no $LOCAL_ALBUM_LAUNCHER"))
            .methods.firstOrNull { it.name == "loadLocalAlbumItem" && it.parameterTypes == listOf("Ljava/lang/String;") }
            ?.let { load ->
                val instructions = load.implementation!!.instructions.toList()
                val await = instructions.indexOfFirst {
                    it.opcode == Opcode.INVOKE_VIRTUAL &&
                        (it as ReferenceInstruction).reference.toString() == "$LATCH->await(JLjava/util/concurrent/TimeUnit;)Z"
                }
                val call = instructions.getOrNull(await) as? FiveRegisterInstruction
                    ?: throw PatchException("$LOCAL_ALBUM_LAUNCHER.loadLocalAlbumItem no longer waits on a latch")
                load.replaceInstruction(
                    await,
                    "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE}, v${call.registerF} }, " +
                        "$OPEN_IN_ALBUM->awaitAlbumItem($LATCH" + "JLjava/util/concurrent/TimeUnit;)Z",
                )
            } ?: throw PatchException("no loadLocalAlbumItem(String) in $LOCAL_ALBUM_LAUNCHER")

        // Off One UI Gallery reads One UI version 0 and keeps to its generic-device paths, which work; but it turns
        // "MX albums" (One UI 5's album model) on only from One UI 5, and this build has the screens only for that
        // model: Favorites and Recent in the menu went nowhere. On for every phone.
        mutableClassDefBy(PREFERENCE_FEATURES).methods.single {
            it.name == "isEnabled" && it.parameterTypes.isEmpty() && it.returnType == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags)
        }.let { isEnabled ->
            isEnabled.addInstructionsWithLabels(
                0,
                """
                    sget-object v0, $PREFERENCE_FEATURES->MxAlbums:$PREFERENCE_FEATURES
                    if-ne p0, v0, :feature
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("feature", isEnabled.implementation!!.instructions.first()),
            )
        }
    }
}
