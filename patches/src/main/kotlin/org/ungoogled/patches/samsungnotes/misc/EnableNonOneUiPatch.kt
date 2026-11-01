package org.ungoogled.patches.samsungnotes.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsungnotes.SamsungNotesConstants.COMPATIBILITY_SAMSUNG_NOTES
import org.w3c.dom.Element

internal const val SAMSUNG_NOTES_EXTENSION = "extensions/samsungnotes.mpe"

/**
 * Off One UI the libraries Samsung's system provides are missing. Notes requires one Java library,
 * com.samsung.device, which alone stops the install; it and every native library become optional, and the features
 * built on them stay off. Its system-app multiArch flag goes too, so it installs with stripped libraries.
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

            // A system app's multiArch: Android then demands native code for every ABI the phone runs, 32-bit too.
            // With the 32-bit libraries left out (Morphe Manager's "strip libraries"), a phone that also runs
            // 32-bit code refuses the install ("multiArch app's native libs don't support all the natively
            // supported ABIs"). Notes runs in its own process only, so its primary ABI is all it needs.
            (manifest.getElementsByTagName("application").item(0) as Element).removeAttribute("android:multiArch")
        }
    }
}

/** Sends Notes' uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_NOTES_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungnotes/OneUi;", "Lorg/ungoogled/samsungnotes/Sem;")
        // Measured against 4.4.48.15: 112 calls and 22 field accesses.
        if (calls < 60 || fields < 10) throw PatchException("only $calls One UI calls and $fields field accesses found")
        java.util.logging.Logger.getLogger("SamsungNotes").info("One UI members: $calls calls, $fields fields")
    }
}

/**
 * Notes also calls 71 classes only One UI has (SemFloatingFeature first, at the notes list). The extension carries a
 * generated stand-in for each, with just the members Notes uses, answering "absent"; on a Samsung phone the
 * framework's own classes load first, so they never run there.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Notes installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_NOTES)
    dependsOn(manifestPatch, oneUiMembersPatch)
    extendWith(SAMSUNG_NOTES_EXTENSION)
}
