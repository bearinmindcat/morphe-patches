package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PHOTO_EDITS = "Lorg/ungoogled/samsunggallery/PhotoEdits;"
private const val PHOTO_COMMAND = "Lcom/samsung/android/gallery/app/controller/externals/StartSimplePhotoEditorCmd;"
private const val GIF_COMMAND = "Lcom/samsung/android/gallery/app/controller/externals/StartAgifEditorCmd;"
private const val MEDIA_ITEM = "Lcom/samsung/android/gallery/module/data/MediaItem;"
private const val CONTENT_URI = "Lcom/samsung/android/gallery/module/data/ContentUri;"
private const val BASE_COMMAND = "Lcom/samsung/android/gallery/app/controller/BaseCommand;"
private const val START_ACTIVITY = "Landroid/app/Activity;->startActivity(Landroid/content/Intent;)V"
private const val STORE_GUIDES = "Lorg/ungoogled/samsunggallery/StoreGuides;"
private const val MISSING_PACKAGE = "Lcom/samsung/android/gallery/widget/dialog/MissingPackage;"
private const val DOWNLOAD_BUILD =
    "Lcom/samsung/android/gallery/widget/dialog/MissingPackage\$DownloadBuilder;->build(Landroid/content/Context;)Landroid/app/AlertDialog;"
private const val GUIDE_DOWNLOAD = "->guideDownloadPackage(Ljava/lang/String;Ljava/lang/String;)V"

/**
 * Edit on a photo opens Samsung's Photo Editor. Without it Gallery offered the Galaxy Store, which off One UI only says
 * "Can't download right now"; the photo now goes to the phone's own photo editors, or a message says there is none.
 * A GIF always goes there: Photo Editor's GIF editor needs One UI's GIF decoder and closes at once.
 */
internal val photoEditPatch = bytecodePatch(
    description = "Opens a photo editor from a picture's Edit.",
) {
    extendWith(SAMSUNG_GALLERY_EXTENSION)

    execute {
        fun MutableMethod.requireStoreGuide(command: String, registers: Int = 2) {
            val guides = implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString()?.endsWith(GUIDE_DOWNLOAD) == true
            } == true
            if (!guides) throw PatchException("$command no longer offers the Galaxy Store when Photo Editor is missing")
            val locals = implementation!!.registerCount - parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } - 1
            if (locals < registers) throw PatchException("$command.$name has $locals free registers, needs $registers")
        }

        // startEditor(MediaItem, PhotoEditorMode, byte[], boolean): the item is p1.
        val photo = mutableClassDefByOrNull(PHOTO_COMMAND) ?: throw PatchException("Samsung Gallery has no $PHOTO_COMMAND")
        val startEditor = photo.methods.firstOrNull {
            it.returnType == "V" && it.parameterTypes.size == 4 && it.parameterTypes[0] == MEDIA_ITEM &&
                it.parameterTypes[2] == "[B" && it.parameterTypes[3] == "Z"
        } ?: throw PatchException("no startEditor (MediaItem, mode, byte[], boolean) in $PHOTO_COMMAND")
        startEditor.requireStoreGuide(PHOTO_COMMAND)
        startEditor.addInstructionsWithLabels(
            0,
            """
                invoke-virtual { p0 }, $BASE_COMMAND->getActivity()Landroid/app/Activity;
                move-result-object v0
                invoke-static { p1 }, $CONTENT_URI->getUri(Lcom/samsung/android/gallery/database/dbtype/FileItemInterface;)Landroid/net/Uri;
                move-result-object v1
                invoke-static { v0, v1 }, $PHOTO_EDITS->openElsewhere(Landroid/app/Activity;Landroid/net/Uri;)Z
                move-result v0
                if-eqz v0, :launch
                return-void
            """,
            ExternalLabel("launch", startEditor.implementation!!.instructions.first()),
        )

        // Should Gallery's own launch still fail (ActivityNotFoundException), its catch offered the Galaxy Store: the photo
        // goes to the same chooser instead.
        val guides = startEditor.implementation!!.instructions.withIndex().filter { (_, insn) ->
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                ((insn as ReferenceInstruction).reference as MethodReference).toString().endsWith(GUIDE_DOWNLOAD)
        }
        val guide = guides.singleOrNull() ?: throw PatchException("${guides.size} Galaxy Store guides in $PHOTO_COMMAND startEditor, expected 1")
        val guideCall = guide.value as FiveRegisterInstruction
        val free = guideCall.registerD
        startEditor.replaceInstruction(guide.index, "invoke-virtual { v${guideCall.registerC} }, $BASE_COMMAND->getActivity()Landroid/app/Activity;")
        startEditor.addInstructions(
            guide.index + 1,
            """
                move-result-object v$free
                invoke-static { v$free }, $PHOTO_EDITS->launchFailed(Landroid/app/Activity;)V
            """,
        )

        // onExecute starts Photo Editor's GIF editor by its action with getActivity().startActivity(intent): that call
        // goes through the extension, which sends the GIF elsewhere when nothing answers the action.
        val gif = mutableClassDefByOrNull(GIF_COMMAND) ?: throw PatchException("Samsung Gallery has no $GIF_COMMAND")
        val onExecute = gif.methods.firstOrNull { it.name == "onExecute" && it.parameterTypes.lastOrNull() == "[Ljava/lang/Object;" }
            ?: throw PatchException("no onExecute in $GIF_COMMAND")
        onExecute.requireStoreGuide(GIF_COMMAND, registers = 0)
        val starts = onExecute.implementation!!.instructions.withIndex().filter { (_, insn) ->
            insn.opcode == Opcode.INVOKE_VIRTUAL &&
                ((insn as ReferenceInstruction).reference as MethodReference).toString() == START_ACTIVITY
        }
        val launch = starts.singleOrNull() ?: throw PatchException("${starts.size} startActivity calls in $GIF_COMMAND, expected 1")
        val call = launch.value as FiveRegisterInstruction
        onExecute.replaceInstruction(
            launch.index,
            "invoke-static { v${call.registerC}, v${call.registerD} }, " +
                "$PHOTO_EDITS->startGifEditor(Landroid/app/Activity;Landroid/content/Intent;)V",
        )

        // Samsung's Galaxy Store dialog ("Can't download right now"): logged with who asked, and held back while an
        // editor chooser that took Photo Editor's or Editor Lite's place has just opened (StoreGuides).
        val missing = mutableClassDefByOrNull(MISSING_PACKAGE) ?: throw PatchException("Samsung Gallery has no $MISSING_PACKAGE")
        val request = missing.methods.firstOrNull {
            it.name == "showGotoStoreIfDeleted" && it.returnType == "Z" && it.parameterTypes.size == 5 &&
                it.parameterTypes[1] == "Ljava/lang/String;"
        } ?: throw PatchException("no showGotoStoreIfDeleted(Context, String, String, boolean, listener) in $MISSING_PACKAGE")
        request.addInstructions(0, "invoke-static { p1 }, $STORE_GUIDES->requested(Ljava/lang/String;)V")
        val shows = missing.methods.mapNotNull { m ->
            val insns = m.implementation?.instructions?.toList() ?: return@mapNotNull null
            if (insns.none { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == DOWNLOAD_BUILD }) return@mapNotNull null
            val show = insns.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL && ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                    r.name == "show" && r.parameterTypes.isEmpty() && r.returnType == "V"
                }
            }
            if (show < 0) null else m to show
        }
        val (lambda, show) = shows.singleOrNull()
            ?: throw PatchException("${shows.size} places show MissingPackage's download dialog, expected 1")
        val dialog = (lambda.implementation!!.instructions.elementAt(show) as FiveRegisterInstruction).registerC
        lambda.replaceInstruction(show, "invoke-static { v$dialog }, $STORE_GUIDES->show(Landroid/app/AlertDialog;)V")
    }
}
