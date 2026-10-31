package org.ungoogled.patches.samsungphotoeditor.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
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
        // Measured against 3.9.04.6: 186 calls and 45 field accesses.
        if (calls < 100) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * Native libraries only One UI has: Samsung system libraries the app loads by name, and the app's own libraries that
 * link against one (light balance, beauty, segmentation, tilt, face, matting). Measured against 3.9.04.6.
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
        val (implemented, nothing) = replaceSdkStubs(
            mapOf("Lcom/samsung/android/media/SemQrBitmapFactory;" to "Lorg/ungoogled/samsungphotoeditor/QuramCodec;"),
        )
        java.util.logging.Logger.getLogger("SamsungPhotoEditor").info("SDK stubs: $implemented implemented, $nothing answer nothing")
        if (implemented < 8) throw PatchException("only $implemented Quram codec stubs found")
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
    dependsOn(manifestPatch, oneUiMembersPatch, sprDrawablesPatch, nativeLibrariesPatch, sdkStubsPatch, absentProvidersPatch)
    extendWith(SAMSUNG_PHOTO_EDITOR_EXTENSION)
}
