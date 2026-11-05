package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val ABI = "arm64-v8a"
private const val LIBRARY = "libgallerynn.so"

/**
 * The runtime of the on-device models (ncnn and each network's glue, extensions/samsunggallery/native: Extract text's
 * text recognizer, Search by content's image model) goes into the APK as a native library; Gallery itself is 64-bit
 * ARM only. The models are not in it: they are downloaded on first use. Gallery keeps its libraries uncompressed in
 * the APK (extractNativeLibs false); an added file is compressed, so the libraries are now unpacked at install instead.
 */
internal val nativeLibraryPatch = resourcePatch(
    description = "Adds the on-device models' runtime to Samsung Gallery.",
) {
    execute {
        if (listApkEntries("lib/").none { it.startsWith("lib/$ABI/") }) {
            throw PatchException("Samsung Gallery has no $ABI libraries")
        }
        val library = NativeLibraryResources::class.java.classLoader.getResourceAsStream("samsunggallery/lib/$ABI/$LIBRARY")
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

private object NativeLibraryResources
