package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import org.ungoogled.patches.samsunggallery.SamsungGalleryConstants.COMPATIBILITY_SAMSUNG_GALLERY
import org.w3c.dom.Element

/** Samsung's OCR SDK, bundled in Gallery with its names kept. */
private const val RECOGNIZER = "Lcom/samsung/android/sdk/ocr/Recognizer;"
private const val I_RECOGNIZER = "Lcom/samsung/android/sdk/ocr/IRecognizer;"
private const val SAFE_CLOSER = "Lcom/samsung/android/sdk/ocr/SafeCloser;"
private const val OCR_TYPE = "Lcom/samsung/android/sdk/ocr/OCRType;"
private const val OCR_LANGUAGE = "Lcom/samsung/android/sdk/ocr/OCRLanguage;"
/** The text extraction library's feature flags (One UI version checks), names kept. */
private const val TEXT_EXTRACTION_RUNE = "Lcom/samsung/android/app/sdk/deepsky/textextraction/util/Rune;"
private const val EXTRACT_TEXT = "Lorg/ungoogled/samsunggallery/ocr/ExtractText;"
/** The selection's haptic feedback, names kept. */
private const val VIBRATION_HELPER = "Lcom/samsung/android/app/sdk/deepsky/textextraction/selectionui/helper/VibrationHelper;"

private const val ABI = "arm64-v8a"
private const val LIBRARY = "libgalleryocr.so"

/**
 * The text recognizer's runtime (ncnn and the two networks' glue, extensions/samsunggallery/native) goes into the APK
 * as a native library; Gallery itself is 64-bit ARM only. The models are not in it: they are downloaded on first use.
 * Gallery keeps its libraries uncompressed in the APK (extractNativeLibs false); an added file is compressed, so the
 * libraries are now unpacked at install instead.
 */
private val nativeLibraryPatch = resourcePatch(
    description = "Adds the text recognizer's runtime to Samsung Gallery.",
) {
    execute {
        if (listApkEntries("lib/").none { it.startsWith("lib/$ABI/") }) {
            throw PatchException("Samsung Gallery has no $ABI libraries")
        }
        val library = ExtractTextResources::class.java.classLoader.getResourceAsStream("samsunggallery/lib/$ABI/$LIBRARY")
            ?: throw PatchException("$LIBRARY is missing from the patches")
        get("lib/$ABI/$LIBRARY").apply {
            parentFile.mkdirs()
            outputStream().use { out -> library.use { it.copyTo(out) } }
        }
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.setAttribute("android:extractNativeLibs", "true")
        }
    }
}

private object ExtractTextResources

@Suppress("unused")
val extractTextPatch = bytecodePatch(
    name = "Extract text",
    description = "Brings back Extract text with an on-device text recognizer.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_GALLERY)
    dependsOn(enableNonOneUiPatch, nativeLibraryPatch)
    extendWith(SAMSUNG_GALLERY_EXTENSION)

    execute {
        // Gallery's "Extract text" (the T button) is built on Samsung's OCR SDK, whose engine is part of One UI: off
        // One UI the SDK finds none and the button never shows. The SDK now asks our recognizer instead.
        val recognizer = mutableClassDefByOrNull(RECOGNIZER) ?: throw PatchException("no Samsung OCR SDK in Gallery")

        // "Is there an engine?" (Samsung reads it from One UI's floating features): ours, unless the user declined.
        recognizer.methods.firstOrNull {
            it.name == "isSupported" && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes == listOf("Landroid/content/Context;", OCR_TYPE) && it.returnType == "Z"
        }?.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTRACT_TEXT->isSupported(Landroid/content/Context;)Z
                move-result v0
                return v0
            """,
        ) ?: throw PatchException("no Recognizer.isSupported(Context, OCRType)")

        // The recognizer picks Samsung's engine by the One UI libraries it finds; it now takes ours. The rest of the
        // SDK (result conversion, selection, copy, share) runs unchanged on what ours returns.
        if (recognizer.fields.none { it.name == "instance" && it.type == I_RECOGNIZER } ||
            recognizer.fields.none { it.name == "mSafeCloser" && it.type == SAFE_CLOSER }
        ) {
            throw PatchException("Recognizer's engine fields are not instance / mSafeCloser")
        }
        val constructor = recognizer.methods.firstOrNull {
            it.name == "<init>" && it.parameterTypes == listOf("Landroid/content/Context;", OCR_TYPE, OCR_LANGUAGE, "Z")
        } ?: throw PatchException("no Recognizer(Context, OCRType, OCRLanguage, boolean)")
        if (constructor.implementation!!.registerCount - 5 < 2) throw PatchException("Recognizer constructor has no spare registers")
        constructor.addInstructionsWithLabels(
            0,
            """
                invoke-static { p1 }, $EXTRACT_TEXT->recognizer(Landroid/content/Context;)Ljava/lang/Object;
                move-result-object v0
                if-eqz v0, :samsung
                invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                check-cast v0, $I_RECOGNIZER
                iput-object v0, p0, $RECOGNIZER->instance:$I_RECOGNIZER
                new-instance v0, $SAFE_CLOSER
                invoke-static { p0 }, Ljava/lang/System;->identityHashCode(Ljava/lang/Object;)I
                move-result v1
                invoke-direct { v0, v1 }, $SAFE_CLOSER-><init>(I)V
                iput-object v0, p0, $RECOGNIZER->mSafeCloser:$SAFE_CLOSER
                return-void
            """,
            ExternalLabel("samsung", constructor.implementation!!.instructions.first()),
        )

        // The text extraction library also wants One UI 4.1.1 or later (One UI's version reads 0 elsewhere).
        mutableClassDefByOrNull(TEXT_EXTRACTION_RUNE)?.methods?.firstOrNull {
            it.name == "isSupportOcr" && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }?.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            ?: throw PatchException("no isSupportOcr in the text extraction library")

        // Selecting text vibrates with One UI's own waveform type (VibrationEffect.SemMagnitudeType), a class other
        // phones don't have: press and hold on text crashed Gallery. Android's own tick and click instead.
        mutableClassDefByOrNull(VIBRATION_HELPER)?.let { helper ->
            if (helper.fields.none { it.name == "isLongPress" && it.type == "Z" }) throw PatchException("VibrationHelper has no isLongPress")
            helper.methods.firstOrNull {
                it.name == "createVibrationEffect" && it.parameterTypes.isEmpty() && it.returnType == "Landroid/os/VibrationEffect;"
            }
        }?.addInstructionsWithLabels(
            0,
            """
                iget-boolean v0, p0, $VIBRATION_HELPER->isLongPress:Z
                if-eqz v0, :tick
                const/4 v0, 0x0
                goto :effect
                :tick
                const/4 v0, 0x2
                :effect
                invoke-static { v0 }, Landroid/os/VibrationEffect;->createPredefined(I)Landroid/os/VibrationEffect;
                move-result-object v0
                return-object v0
            """,
        ) ?: throw PatchException("no createVibrationEffect in the text selection's VibrationHelper")
    }
}
