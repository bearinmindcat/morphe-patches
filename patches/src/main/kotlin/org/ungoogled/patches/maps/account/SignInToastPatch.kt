package org.ungoogled.patches.maps.account

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.maps.microg.MicrogSelection
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

internal const val SIGN_IN = "Lorg/ungoogled/ui/SignIn;"

internal val signInToastPatch = bytecodePatch(
    description = "The \"Sign in\" pill shows a \"Can't sign in\" toast instead of failing silently.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch)

    execute {
        if (MicrogSelection.replaces(this, "Remove sign-in prompts")) return@execute
        // The pill's click case, in a shared switch-dispatched onClick(View): cast the view, click it, return.
        // The tap ends in GMS connection plumbing that gets no real provider and does nothing.
        val pill = SignInButtonWrapperFingerprint.classDef.type
        val click = Fingerprint(
            name = "onClick",
            parameters = listOf("Landroid/view/View;"),
            filters = listOf(
                checkCast(pill),
                methodCall(definingClass = pill, name = "performClick", opcode = Opcode.INVOKE_VIRTUAL, location = MatchAfterImmediately()),
                opcode(Opcode.RETURN_VOID, MatchAfterImmediately()),
            ),
        )
        val matches = click.matchAll()
        val match = matches.singleOrNull() ?: throw PatchException("sign-in pill click: found ${matches.size}")
        val cast = match.instructionMatches.first().index
        val view = (match.method.implementation!!.instructions[cast] as OneRegisterInstruction).registerA
        // The toast goes right after the cast (the view is only a View once cast). Nothing is removed; the
        // original click and return are left behind the toast's own return, unreachable.
        match.method.addInstructions(
            cast + 1,
            """
                invoke-static/range { v$view .. v$view }, $SIGN_IN->cantSignIn(Ljava/lang/Object;)V
                return-void
            """,
        )
    }
}
