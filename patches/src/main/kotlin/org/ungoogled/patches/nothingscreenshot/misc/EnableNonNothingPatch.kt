package org.ungoogled.patches.nothingscreenshot.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.nothingscreenshot.NothingScreenshotConstants.COMPATIBILITY_NOTHING_SCREENSHOT
import org.w3c.dom.Element

internal const val NOTHING_SCREENSHOT_EXTENSION = "extensions/nothingscreenshot.mpe"

private const val EXT = "Lorg/ungoogled/nothingscreenshot"
private const val OPEN_ACTIVITY = "org.ungoogled.nothingscreenshot.OpenActivity"
private const val EDIT_ACTION = "nothing.intent.action.NT_SCREENSHOT_EDIT"

/** DeleteViewModel.removeFileFromMedia(Uri): deletes an image's file and its media entry. */
private object RemoveFileFromMediaFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/net/Uri;"),
    strings = listOf("DeleteViewModel", "delete failed"),
)

/** SaveViewModel.save's work: exports the edit, then deletes the image it replaces. */
private object SaveFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("save occur error"),
)

/**
 * The editor (MainActivity) opens only for Nothing's screenshot preview: an action of Nothing's own and a permission
 * only apps signed like it hold. A new screen (OpenActivity) is the way in: a launcher icon that picks an image first,
 * and "Edit" for images in other apps.
 */
private val entryPointsPatch = resourcePatch(
    description = "Adds a launcher icon and an Edit entry for images.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val activities = manifest.getElementsByTagName("activity")
            val editor = (0 until activities.length).map { activities.item(it) as Element }.firstOrNull { activity ->
                activity.getElementsByTagName("action").let { actions ->
                    (0 until actions.length).any { (actions.item(it) as Element).getAttribute("android:name") == EDIT_ACTION }
                }
            } ?: throw PatchException("no screenshot editor (action $EDIT_ACTION) in the manifest")
            val application = editor.parentNode as Element

            fun filter(action: String, category: String, mimeType: String? = null) =
                manifest.createElement("intent-filter").apply {
                    appendChild(manifest.createElement("action").apply { setAttribute("android:name", action) })
                    appendChild(manifest.createElement("category").apply { setAttribute("android:name", category) })
                    if (mimeType != null) {
                        appendChild(manifest.createElement("data").apply { setAttribute("android:mimeType", mimeType) })
                    }
                }
            application.appendChild(
                manifest.createElement("activity").apply {
                    setAttribute("android:name", OPEN_ACTIVITY)
                    setAttribute("android:exported", "true")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                    setAttribute("android:excludeFromRecents", "true")
                    appendChild(filter("android.intent.action.MAIN", "android.intent.category.LAUNCHER"))
                    appendChild(filter("android.intent.action.EDIT", "android.intent.category.DEFAULT", "image/*"))
                },
            )
        }
    }
}

/**
 * Nothing Screenshot's editor (crop, pen, mosaic, save, share) for any image on any ROM. On Nothing OS it opens from
 * Nothing's screenshot preview only and saving an edit deletes the screenshot it was opened on. Here it opens from its
 * own launcher icon or another app's "Edit", and saving keeps the original (see Screenshots). Partial screenshots (a
 * region of the screen, drawn by Nothing's system UI with a system permission) are not part of this.
 */
@Suppress("unused")
val enableNonNothingPatch = bytecodePatch(
    name = "Enable non-Nothing OS ROM support",
    description = "Opens Nothing Screenshot's editor on any image on non-Nothing OS ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NOTHING_SCREENSHOT)
    dependsOn(entryPointsPatch)
    extendWith(NOTHING_SCREENSHOT_EXTENSION)

    execute {
        val viewModel = RemoveFileFromMediaFingerprint.method.definingClass
        RemoveFileFromMediaFingerprint.method.apply {
            val getApp = mutableClassDefBy(definingClass).methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == "Landroid/app/Application;"
            } ?: throw PatchException("no Application getter in $definingClass")
            addInstructionsWithLabels(
                0,
                """
                    invoke-virtual { p0 }, $definingClass->${getApp.name}()Landroid/app/Application;
                    move-result-object v0
                    invoke-static { v0, p1 }, $EXT/Screenshots;->keep(Landroid/content/Context;Landroid/net/Uri;)Z
                    move-result v0
                    if-eqz v0, :delete
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("delete", getInstruction(0)),
            )
        }

        // The save marks the delete of the image it replaces, which the Delete button's does not have.
        SaveFingerprint.method.apply {
            val index = implementation!!.instructions.indexOfFirst {
                val r = (it as? ReferenceInstruction)?.reference as? MethodReference
                r != null && r.definingClass == viewModel && r.name == "delete" &&
                    r.parameterTypes.map { p -> p.toString() } == listOf("Lkotlin/jvm/functions/Function1;")
            }
            if (index < 0) throw PatchException("the save does not delete through $viewModel")
            addInstruction(index, "invoke-static { }, $EXT/Screenshots;->saving()V")
        }
    }
}
