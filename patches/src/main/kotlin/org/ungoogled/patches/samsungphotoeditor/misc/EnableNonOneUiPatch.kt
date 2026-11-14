package org.ungoogled.patches.samsungphotoeditor.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.galaxyStoreChecksPatch
import org.ungoogled.patches.samsung.implementOneUiInterfaceMethods
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.stubNativeLibraries
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.replaceSdkStubs
import org.ungoogled.patches.samsung.replaceSprDrawables
import org.ungoogled.patches.samsungphotoeditor.SamsungPhotoEditorConstants.COMPATIBILITY_SAMSUNG_PHOTO_EDITOR
import org.w3c.dom.Element

internal const val SAMSUNG_PHOTO_EDITOR_EXTENSION = "extensions/samsungphotoeditor.mpe"

/**
 * Off One UI the libraries Samsung's system provides are missing. Photo Editor requires Samsung's "saiv" Java library
 * and two Quram codec native libraries, which alone stop the install; they and every other one become optional, and
 * the features built on them stay off. The editor is also offered for "Edit" from other apps, as only Samsung Gallery
 * knows to open it.
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            var required = 0
            for (tag in listOf("uses-library", "uses-native-library")) {
                val list = manifest.getElementsByTagName(tag)
                for (i in 0 until list.length) {
                    val element = list.item(i) as Element
                    if (element.getAttribute("android:required") != "false") required++
                    element.setAttribute("android:required", "false")
                }
            }
            if (required == 0) throw PatchException("no required Samsung library in the manifest")

            // Only Samsung Gallery opens the editor (by name); elsewhere it is offered as an editor for any photo.
            val activities = manifest.getElementsByTagName("activity")
            val editor = (0 until activities.length).map { activities.item(it) as Element }
                .firstOrNull { it.getAttribute("android:name") == "com.sec.android.mimage.photoretouching.SPEActivity" }
                ?: throw PatchException("SPEActivity not found in the manifest")
            editor.appendChild(
                manifest.createElement("intent-filter").apply {
                    appendChild(manifest.createElement("action").apply { setAttribute("android:name", "android.intent.action.EDIT") })
                    appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.DEFAULT") })
                    appendChild(manifest.createElement("data").apply { setAttribute("android:mimeType", "image/*") })
                },
            )
        }
    }
}

/** One UI's SPR vector images, which other ROMs cannot decode, become plain vector icons. */
private val sprDrawablesPatch = resourcePatch(
    description = "Replaces One UI's SPR images with plain vector icons.",
) {
    execute {
        val replaced = replaceSprDrawables()
        java.util.logging.Logger.getLogger("SamsungPhotoEditor").info("SPR drawables: $replaced")
    }
}

/** Sends Photo Editor's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_PHOTO_EDITOR_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers(
            "Lorg/ungoogled/samsungphotoeditor/OneUi;", "Lorg/ungoogled/samsungphotoeditor/Sem;",
        )
        val implemented = implementOneUiInterfaceMethods("Lorg/ungoogled/samsungphotoeditor/OneUi;")
        java.util.logging.Logger.getLogger("SamsungPhotoEditor")
            .info("One UI members: $calls calls, $fields fields, $implemented inherited methods")
        // Measured: 3.9.04.6 186 calls and 45 field accesses, 3.4.14.63 93 and 20.
        if (calls < 60) throw PatchException("only $calls One UI calls found")
    }
}

private const val ANDROID_14 = "Lorg/ungoogled/samsungphotoeditor/Android14;"

/**
 * Android 14 methods Photo Editor 3.4.14.63 (Android 13 and up) calls without checking the version: Ultra HDR
 * (gainmaps, the display's HDR/SDR ratio). On Android 13 opening a photo died on Display.isHdrSdrRatioAvailable().
 * Found with api-versions.xml (since > 33) over the app's dex; calls on gainmaps themselves need none (no gainmap
 * exists below Android 14).
 */
private val ANDROID_14_CALLS = listOf(
    "Landroid/graphics/Bitmap;" to "hasGainmap()Z",
    "Landroid/graphics/Bitmap;" to "getGainmap()Landroid/graphics/Gainmap;",
    "Landroid/graphics/Bitmap;" to "setGainmap(Landroid/graphics/Gainmap;)V",
    "Landroid/view/Display;" to "isHdrSdrRatioAvailable()Z",
    "Landroid/view/Display;" to "getHdrSdrRatio()F",
    "Landroid/view/Display;" to "registerHdrSdrRatioChangedListener(Ljava/util/concurrent/Executor;Ljava/util/function/Consumer;)V",
    "Landroid/view/Display;" to "unregisterHdrSdrRatioChangedListener(Ljava/util/function/Consumer;)V",
    "Landroid/view/SurfaceControl\$Transaction;" to
        "setExtendedRangeBrightness(Landroid/view/SurfaceControl;FF)Landroid/view/SurfaceControl\$Transaction;",
).associate { (owner, call) ->
    val params = call.substringAfter('(')
    "$owner->$call" to "$ANDROID_14->${call.substringBefore('(')}($owner$params"
}

/** The build for Android 13 and 14 (see SamsungPhotoEditorConstants). */
private fun isAndroid13Build(version: String?) = version == "3.4.14.63"

/**
 * Photo Editor 3.4.14.63 runs on Android 13 but calls a few Android 14 methods (Ultra HDR) unchecked: they go through
 * Android14, which calls them from Android 14 on and answers "no HDR" on Android 13.
 */
private val android14CallsPatch = bytecodePatch(
    description = "Answers Android 14's HDR calls on Android 13.",
) {
    extendWith(SAMSUNG_PHOTO_EDITOR_EXTENSION)

    execute {
        if (!isAndroid13Build(packageMetadata.versionName)) return@execute
        val moved = redirectCalls(ANDROID_14_CALLS)
        java.util.logging.Logger.getLogger("SamsungPhotoEditor").info("Android 14 calls: $moved")
        // Measured against 3.4.14.63: 37 calls.
        if (moved < 10) throw PatchException("only $moved Android 14 calls found")
    }
}

/**
 * Native libraries only One UI has: Samsung system libraries the app loads by name, and the app's own libraries that
 * link against one (light balance, beauty, segmentation, tilt, face, matting). Measured against 3.9.04.6; 3.4.14.63
 * adds its own matting, photo enhance and LVM libraries (all built on One UI's OpenCV).
 */
private val ONE_UI_LIBRARIES = setOf(
    "apex_motionphoto_utils_jni.media.samsung", "Beauty_v4.camera.samsung", "BestComposition.polarr", "Feature.polarr",
    "ImageScreener.camera.samsung", "MyFilter.camera.samsung", "objectcapture_jni.arcsoft",
    "portrait_controller_engine.camera.samsung", "portrait_controller_interface_jni.camera.samsung",
    "PortraitSolution.camera.samsung", "secbufferhandler.camera.samsung", "smart_cropping",
    "smart_cropping.camera.samsung", "sume_jni.media.samsung", "sum_jni.media.samsung", "Tracking.polarr",
    "veframework.videoeditor.samsung",
    "lightbalance", "PhotoEditorBeauty", "bgpsegmenter.samsung", "FaceOffWrapper.camera.samsung", "GaussianInterface",
    "SRIBLVMUtilityInterface", "tiltEngine",
    "MattingInterface", "PhotoEnhance", "SRIBLVMInterface",
)

/**
 * Photo Editor's tools load native libraries only One UI has, and opening a photo died at the first (light balance).
 * Loading one is no longer fatal, and the classes bound to them answer nothing, so those tools do nothing.
 */
private val nativeLibrariesPatch = bytecodePatch(
    description = "Turns tools built on One UI's native libraries into no-ops.",
) {
    extendWith(SAMSUNG_PHOTO_EDITOR_EXTENSION)

    execute {
        // Before the loads move: classes are matched by their own System.loadLibrary call.
        val stubbed = stubNativeLibraries(ONE_UI_LIBRARIES)
        val moved = redirectCalls(
            mapOf(
                "Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V" to
                    "$SERVICES->loadLibrary(Ljava/lang/String;)V",
                "Ljava/lang/System;->load(Ljava/lang/String;)V" to
                    "$SERVICES->load(Ljava/lang/String;)V",
            ),
        )
        java.util.logging.Logger.getLogger("SamsungPhotoEditor").info("Native libraries: $moved loads, stubbed $stubbed")
        if (moved == 0 || stubbed.isEmpty()) throw PatchException("no native library loads found")
    }
}

/**
 * Photo Editor decodes and saves photos with One UI's Quram codec (SemQrBitmapFactory) and carries only its SDK
 * stubs, which throw off One UI: opening a photo died there. The codec is answered on Android's own BitmapFactory;
 * the other bundled stubs (Samsung's GIF codec, S Pen renderer) answer nothing.
 */
private val sdkStubsPatch = bytecodePatch(
    description = "Decodes and saves photos with Android's own codecs.",
) {
    extendWith(SAMSUNG_PHOTO_EDITOR_EXTENSION)

    execute {
        // 3.4 carries its own Quram codec (com.quramsoft.images, with its library) instead of One UI's.
        val ownCodec = mutableClassDefByOrNull("Lcom/samsung/android/media/SemQrBitmapFactory;") == null &&
            mutableClassDefByOrNull("Lcom/quramsoft/images/QrBitmapFactory;") != null
        val (implemented, nothing) = replaceSdkStubs(
            mapOf("Lcom/samsung/android/media/SemQrBitmapFactory;" to "Lorg/ungoogled/samsungphotoeditor/QuramCodec;"),
        )
        java.util.logging.Logger.getLogger("SamsungPhotoEditor").info("SDK stubs: $implemented implemented, $nothing answer nothing")
        if (implemented < 8 && !ownCodec) throw PatchException("only $implemented Quram codec stubs found")
        if (ownCodec) {
            // Its native half is One UI's libimagecodec.quram.so: opening a photo died with UnsatisfiedLinkError.
            val codec = "Lorg/ungoogled/samsungphotoeditor/QuramCodec;"
            val qr = "Lcom/quramsoft/images/QrBitmapFactory;"
            val quram = "Lcom/quramsoft/images/QuramBitmapFactory;"
            val options = "Lcom/quramsoft/images/QrBitmapFactory\$Options;"
            val bitmap = "Landroid/graphics/Bitmap;"
            val string = "Ljava/lang/String;"
            val calls = mapOf(
                "$qr->decodeFile($string$options)$bitmap" to "$codec->decodeFile(${string}Ljava/lang/Object;)$bitmap",
                "$qr->decodeFile($string${options}III)$bitmap" to "$codec->decodeFile(${string}Ljava/lang/Object;III)$bitmap",
                "$qr->decodeStream(Ljava/io/InputStream;$options)$bitmap" to
                    "$codec->decodeStream(Ljava/io/InputStream;Ljava/lang/Object;)$bitmap",
                "$qr->decodeStream(Ljava/io/InputStream;II$options)$bitmap" to
                    "$codec->decodeStream(Ljava/io/InputStream;IILjava/lang/Object;)$bitmap",
                "$qr->decodeFileDescriptor(Ljava/io/FileDescriptor;Landroid/graphics/Rect;$options)$bitmap" to
                    "$codec->decodeFileDescriptor(Ljava/io/FileDescriptor;Landroid/graphics/Rect;Ljava/lang/Object;)$bitmap",
                "$qr->compressToFile($bitmap$string${string}I$options)I" to
                    "$codec->compressToFile($bitmap$string${string}ILjava/lang/Object;)I",
                "$qr->compressToFile($bitmap[B$string${string}I$options)I" to
                    "$codec->compressToFile($bitmap[B$string${string}ILjava/lang/Object;)I",
                "$quram->compressToFile($bitmap[B$string${string}I$options)I" to
                    "$codec->compressToFile($bitmap[B$string${string}ILjava/lang/Object;)I",
                "$qr->resizeCompressToFile($bitmap$string${string}IIII$options)I" to
                    "$codec->resizeCompressToFile($bitmap$string${string}IIIILjava/lang/Object;)I",
                "$qr->resizeCompressToFile($bitmap[B$string${string}IIII$options)I" to
                    "$codec->resizeCompressToFile($bitmap[B$string${string}IIIILjava/lang/Object;)I",
                "$quram->getICCProfile($string)[B" to "$codec->getICCProfile($string)[B",
                "Lcom/quramsoft/images/QuramDngBitmap;->getExifInfoFromFile(${string}Lcom/quramsoft/images/QuramDngJavaExif;)I" to
                    "$codec->getExifInfoFromFile(${string}Ljava/lang/Object;)I",
            )
            val moved = redirectCalls(calls)
            java.util.logging.Logger.getLogger("SamsungPhotoEditor").info("Bundled Quram codec calls: $moved")
            // Measured against 3.4.14.63: 21 calls (the app's and the codec's own).
            if (moved < 10) throw PatchException("only $moved bundled Quram codec calls found")
        }
    }
}

private const val SERVICES = "Lorg/ungoogled/samsungphotoeditor/Services;"

/**
 * Saving also writes Samsung's own media database ("secmedia") and other Samsung apps' providers; where they are
 * absent Android throws "Unknown URI" and saving crashed (after the photo was written). Writes and calls to a
 * provider that does not exist do nothing.
 */
private val absentProvidersPatch = bytecodePatch(
    description = "Skips writes to Samsung providers the phone does not have.",
) {
    extendWith(SAMSUNG_PHOTO_EDITOR_EXTENSION)

    execute {
        val resolver = "Landroid/content/ContentResolver;"
        val moved = redirectCalls(
            mapOf(
                "$resolver->update(Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I" to
                    "$SERVICES->update(${resolver}Landroid/net/Uri;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I",
                "$resolver->update(Landroid/net/Uri;Landroid/content/ContentValues;Landroid/os/Bundle;)I" to
                    "$SERVICES->update(${resolver}Landroid/net/Uri;Landroid/content/ContentValues;Landroid/os/Bundle;)I",
                "$resolver->insert(Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;" to
                    "$SERVICES->insert(${resolver}Landroid/net/Uri;Landroid/content/ContentValues;)Landroid/net/Uri;",
                "$resolver->bulkInsert(Landroid/net/Uri;[Landroid/content/ContentValues;)I" to
                    "$SERVICES->bulkInsert(${resolver}Landroid/net/Uri;[Landroid/content/ContentValues;)I",
                "$resolver->delete(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I" to
                    "$SERVICES->delete(${resolver}Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I",
                "$resolver->call(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;" to
                    "$SERVICES->call(${resolver}Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
            ),
        )
        java.util.logging.Logger.getLogger("SamsungPhotoEditor").info("Provider calls: $moved")
        if (moved == 0) throw PatchException("no content provider calls found")
    }
}

/**
 * Off One UI Photo Editor does not install (Samsung's "saiv" and Quram codec libraries are required), then dies on the
 * One UI classes it calls (127 have stand-ins in the extension), on One UI's codec, native libraries and media
 * database. Transform, filters and saving work; tools built on One UI's own engines (portrait, AI, light balance) stay
 * off.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Photo Editor installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_PHOTO_EDITOR)
    dependsOn(
        manifestPatch, oneUiMembersPatch, android14CallsPatch, sprDrawablesPatch, nativeLibrariesPatch, sdkStubsPatch,
        absentProvidersPatch,
        galaxyStoreChecksPatch("Samsung Photo Editor"),
    )
    extendWith(SAMSUNG_PHOTO_EDITOR_EXTENSION)
}
