package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.ungoogled.patches.samsung.redirectToWrappers
import org.ungoogled.patches.samsunggallery.SamsungGalleryConstants.COMPATIBILITY_SAMSUNG_GALLERY
import org.w3c.dom.Element

internal const val SAMSUNG_GALLERY_EXTENSION = "extensions/samsunggallery.mpe"
private const val MEDIA_MIRROR = "Lorg/ungoogled/samsunggallery/MediaMirror;"
private const val MEDIA_QUERIES = "Lorg/ungoogled/samsunggallery/MediaQueries;"

/** The URIs Gallery uses off One UI (its "generic device" table), unobfuscated. */
private const val MEDIA_URI_GED = "Lcom/samsung/android/gallery/support/providers/MediaUriGed;"
private const val PREFERENCE_FEATURES = "Lcom/samsung/android/gallery/support/utils/PreferenceFeatures;"
/** Gallery's Android-only answers to what One UI adds (used where SEM version is 0), unobfuscated. */
private const val GED_API_COMPAT = "Lcom/samsung/android/gallery/support/library/v0/GedApiCompatImpl;"
private const val ACTIVITY_STATE = "Lorg/ungoogled/samsunggallery/ActivityState;"

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
