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

internal const val SAMSUNG_GALLERY_EXTENSION = "extensions/samsunggallery.mpe"
private const val MEDIA_MIRROR = "Lorg/ungoogled/samsunggallery/MediaMirror;"

/** The URIs Gallery uses off One UI (its "generic device" table), unobfuscated. */
private const val MEDIA_URI_GED = "Lcom/samsung/android/gallery/support/providers/MediaUriGed;"
private const val PREFERENCE_FEATURES = "Lcom/samsung/android/gallery/support/utils/PreferenceFeatures;"

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
    dependsOn(manifestPatch, oneUiMembersPatch)
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
