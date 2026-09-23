package org.ungoogled.patches.maps.account

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

private const val TOAST_TEXT = "Can't sign in"

@Suppress("unused")
val signInToastPatch = bytecodePatch(
    name = "Sign-in toast",
    description = "The \"Sign in\" pill shows a \"Can't sign in\" toast instead of failing silently.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        val matches = SignInClickFingerprint.instructionMatches
        val cast = matches.first().index
        val count = matches.last().index - cast + 1
        if (count != 3) throw PatchException("sign-in click case is $count instructions, expected 3")

        // The toast goes right AFTER the cast: the view is only a View once it
        // has been cast, and View.getContext() on the uncast Object is exactly
        // what an earlier version of this patch did -- it removed the cast along
        // with the click, and the verifier rejected the whole dispatcher class.
        // Nothing is removed; the original click and return are left behind the
        // toast's own return, unreachable.
        SignInClickFingerprint.method.addInstructions(
            cast + 1,
            """
                invoke-virtual {v0}, Landroid/view/View;->getContext()Landroid/content/Context;
                move-result-object v0
                const-string v1, "$TOAST_TEXT"
                const/4 v2, 0x0
                invoke-static {v0, v1, v2}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;
                move-result-object v0
                invoke-virtual {v0}, Landroid/widget/Toast;->show()V
                return-void
            """,
        )
    }
}
