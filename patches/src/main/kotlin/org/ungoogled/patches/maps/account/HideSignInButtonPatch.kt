package org.ungoogled.patches.maps.account

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val hideSignInButtonPatch = bytecodePatch(
    name = "Hide sign-in button",
    description = "Removes the \"Sign in\" pill from the account sheet.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        // Every constructor ends by hiding the view. p1 is the Context in all of
        // them and is dead by the time the constructor returns, so it can hold
        // the GONE constant without adding a register.
        SignInButtonWrapperFingerprint.classDef.methods
            .filter { it.name == "<init>" }
            .forEach { constructor ->
                val returns = constructor.implementation!!.instructions
                    .withIndex()
                    .filter { it.value.opcode == Opcode.RETURN_VOID }
                    .map { it.index }
                // Insert back to front so earlier indices stay valid.
                returns.reversed().forEach { index ->
                    constructor.addInstructions(
                        index,
                        """
                            const/16 p1, 0x8
                            invoke-virtual { p0, p1 }, Landroid/view/View;->setVisibility(I)V
                        """,
                    )
                }
            }
    }
}
