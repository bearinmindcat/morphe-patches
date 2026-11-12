package org.ungoogled.patches.xiaomigallery.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SYNC_DOWNLOAD_MANAGER = "Lcom/miui/gallery/ui/SyncDownloadManager;"
private const val SYNC_LINE = "Lcom/miui/gallery/ui/SyncGlobalTextLine;"

/** Photos tab: sets up the backup banner ("Back up your items to Google Photos", or OneDrive's) on Global builds. */
private object BackupBannerFingerprint : Fingerprint(
    definingClass = SYNC_DOWNLOAD_MANAGER,
    returnType = "V",
    parameters = listOf(),
    filters = listOf(methodCall(definingClass = SYNC_DOWNLOAD_MANAGER, name = "initTobBannerManager")),
)

/** Settings: shows the "Google Photos backup" card unless the phone has Xiaomi's OneDrive deal. */
private object SettingsBackupCardFingerprint : Fingerprint(
    definingClass = "Lcom/miui/gallery/ui/GallerySettingsFragment;",
    returnType = "V",
    parameters = listOf(),
    strings = listOf("google_backup_banner", "backup_automatically"),
    filters = listOf(methodCall(definingClass = "Lcom/miui/gallery/util/PhotoModelTypeUtil;", name = "isSupportOneDrive")),
)

/** Photos tab's pull-down line: the counts, then Google Photos' (or OneDrive's) sync state with an action. */
private object SyncLineStatusFingerprint : Fingerprint(
    definingClass = SYNC_LINE,
    name = "onSyncStatusChange",
    returnType = "V",
    parameters = listOf("Lcom/miui/gallery/base/syncstate/SyncStateInfo;"),
    filters = listOf(methodCall(definingClass = SYNC_LINE, name = "onSyncStatusChangeForGoogle")),
)

/** An album's "Turn on/off sync with the cloud" (Global: Google Photos' backup of that folder). */
private abstract class AlbumSyncMenuFingerprint(name: String) : Fingerprint(
    definingClass = "Lcom/miui/gallery/ui/MenuStatusManager;",
    name = name,
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Lcom/miui/gallery/transfer/logic/backup/GoogleBackupHelper;", name = "isBackupOpen"),
    ),
)

private object AlbumSyncOnFingerprint : AlbumSyncMenuFingerprint("canEnableAutoUpload")
private object AlbumSyncOffFingerprint : AlbumSyncMenuFingerprint("canDisableAutoUpload")

/** The card's own "show me" (Settings calls it with true on Global builds). */
private object SettingsBackupCardVisibleFingerprint : Fingerprint(
    definingClass = "Lcom/miui/gallery/transfer/ui/view/GoogleBackupStatusPreference;",
    name = "setPreferenceVisible",
    returnType = "V",
    parameters = listOf("Z"),
)

/**
 * Gallery links to Google Photos through Google's partner backup API, which only answers Xiaomi's own signature
 * ("Error doing handshake with Google Photos, status=PERMISSION_DENIED"): the Photos tab's "Back up your items to Google
 * Photos" banner, its pull-down line's "Keep your photos and videos safe  Turn on", an album's "Turn on sync with the
 * cloud" and the "Google Photos backup" card in Settings did nothing when tapped. All are hidden; the pull-down line
 * keeps the photo and video counts. (MiCloud, the other sync behind that album item, needs a Xiaomi account.)
 */
internal val googlePhotosPatch = bytecodePatch(
    description = "Hides Google Photos backup prompts, which cannot link.",
) {
    execute {
        BackupBannerFingerprint.method.apply {
            val index = implementation!!.instructions.indexOfFirst {
                val r = (it as? ReferenceInstruction)?.reference as? MethodReference
                r?.definingClass == SYNC_DOWNLOAD_MANAGER && r.name == "initTobBannerManager"
            }
            if (index < 0) throw PatchException("Photos tab no longer sets up the backup banner")
            replaceInstruction(index, "nop")
        }
        SyncLineStatusFingerprint.method.apply {
            if (implementation!!.registerCount - 2 < 2) throw PatchException("no local registers in the pull-down line")
            // What the line does when there is no sync state to show: counts only (SyncGlobalTextLine.refresh).
            addInstructions(
                0,
                """
                    invoke-virtual { p0 }, $SYNC_LINE->ensureView()V
                    iget-object v0, p0, $SYNC_LINE->mPanelManager:Lcom/miui/gallery/widget/PanelManager;
                    const/4 v1, 0x0
                    invoke-interface { v0, p0, v1 }, Lcom/miui/gallery/widget/PanelManager;->addItem(Lcom/miui/gallery/widget/PanelItem;Z)Z
                    const/16 v1, 0x8
                    iget-object v0, p0, $SYNC_LINE->mDescView:Landroid/widget/TextView;
                    invoke-virtual { v0, v1 }, Landroid/view/View;->setVisibility(I)V
                    iget-object v0, p0, $SYNC_LINE->mActionView:Landroid/view/View;
                    invoke-virtual { v0, v1 }, Landroid/view/View;->setVisibility(I)V
                    iget-object v0, p0, $SYNC_LINE->mActionTwoView:Landroid/view/View;
                    invoke-virtual { v0, v1 }, Landroid/view/View;->setVisibility(I)V
                    return-void
                """,
            )
        }

        SettingsBackupCardFingerprint.method.apply {
            // The one "setVisible(true)": the card, when the phone has no OneDrive deal.
            val instructions = implementation!!.instructions.toList()
            val index = instructions.indices.singleOrNull { i ->
                val insn = instructions[i]
                val next = instructions.getOrNull(i + 1)
                val r = (next as? ReferenceInstruction)?.reference as? MethodReference
                insn.opcode == Opcode.CONST_4 && (insn as NarrowLiteralInstruction).narrowLiteral == 1 &&
                    r?.name == "setVisible" && (next as FiveRegisterInstruction).registerD == (insn as OneRegisterInstruction).registerA
            } ?: throw PatchException("Settings no longer shows the Google Photos backup card the known way")
            val register = (instructions[index] as OneRegisterInstruction).registerA
            replaceInstruction(index, "const/4 v$register, 0x0")
        }
        SettingsBackupCardVisibleFingerprint.method.addInstructions(0, "const/4 p1, 0x0")
        for (fingerprint in listOf(AlbumSyncOnFingerprint, AlbumSyncOffFingerprint)) {
            fingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        }
    }
}
