package org.ungoogled.patches.maps.account

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.replaceBlockAtLabel

private const val TOAST_TEXT = "Can't sign in"

/**
 * Both fingerprints above match a WHOLE method that is a shared dispatcher for
 * several unrelated menu actions, not a method dedicated to this one row. Every
 * filter in each fingerprint is part of one contiguous span pinning this row's
 * case from its own first instruction to its own last, so only that span -
 * `instructionMatches.first().index` through `.last().index`, inclusive - may
 * be touched. Removing and inserting at index 0, or using any API that
 * replaces "as many old instructions as the new list is long" rather than the
 * span's real length, would corrupt every sibling case sharing the method.
 *
 * Nothing is removed, either: removing the old case body merges its labels --
 * the case label itself among them -- into whatever follows, which is the NEXT
 * case. An earlier version of this patch did exactly that, and the tap ran the
 * neighbouring row's code instead of the toast. The new body is placed AT the
 * case label and returns; the old one is left behind it, unreachable.
 */
@Suppress("unused")
val profileToastPatch = bytecodePatch(
    name = "Your profile toast",
    description = "Tapping \"Your profile\" shows a \"Can't sign in\" toast instead of opening nothing.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // Shapes.SKIP_DISMISS: with Keep account sheet open, the sheet stays up behind the toast.
    dependsOn(sharedExtensionPatch)

    execute {
        // Modern builder: 10 real instructions, this row's whole case.
        ProfileClickModernFingerprint.let { fp ->
            val matches = fp.instructionMatches
            val start = matches.first().index
            val end = matches.last().index
            val count = end - start + 1
            if (count != 10) throw PatchException("modern profile-click case is $count instructions, expected 10")

            fp.method.apply {
                replaceBlockAtLabel(
                    start,
                    """
                        iget-object p0, p0, Lopa;->a:Ljava/lang/Object;
                        check-cast p0, Loyi;
                        iget-object p0, p0, Loyi;->b:Ljava/lang/Object;
                        check-cast p0, Landroid/content/Context;
                        const/4 v1, 0x1
                        sput-boolean v1, $SHAPES->SKIP_DISMISS:Z
                        const-string v0, "$TOAST_TEXT"
                        const/4 v1, 0x0
                        invoke-static {p0, v0, v1}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
                        move-result-object p0
                        invoke-virtual {p0}, Landroid/widget/Toast;->show()V
                        sget-object p0, Lcufx;->a:Lcufx;
                        return-object p0
                    """,
                )
            }
        }

        // Legacy builder: 23 real instructions, this row's whole case (the
        // trace-span try/finally included). classes4.dex sits at the 65,536
        // method_id cap, so only method refs it already contains may be used:
        // Toast.makeText and Toast.show are both already called elsewhere in
        // this dex, and Lolr;->a is an existing field ref.
        ProfileClickLegacyFingerprint.let { fp ->
            val matches = fp.instructionMatches
            val start = matches.first().index
            val end = matches.last().index
            val count = end - start + 1
            if (count != 23) throw PatchException("legacy profile-click case is $count instructions, expected 23")

            fp.method.apply {
                replaceBlockAtLabel(
                    start,
                    """
                        iget-object p0, p0, Lolq;->a:Ljava/lang/Object;
                        check-cast p0, Lolr;
                        iget-object p0, p0, Lolr;->a:Lnxb;
                        const-string v0, "$TOAST_TEXT"
                        const/4 v1, 0x0
                        invoke-static {p0, v0, v1}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
                        move-result-object p0
                        invoke-virtual {p0}, Landroid/widget/Toast;->show()V
                        return-void
                    """,
                )
            }
        }
    }
}
