package org.ungoogled.patches.maps.account

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.Opcode

/**
 * The "Sign in" pill's click case, inside the same kind of shared,
 * switch-dispatched onClick(View) as the legacy profile-click case above.
 * Confirmed live via Frida: the tap reaches the pill wrapper's own
 * performClick(), which funnels into GMS connection plumbing that gets no
 * real provider and silently does nothing. This span covers only that
 * three-instruction tail (cast the already-resolved view, click it, return);
 * whatever loads the view before the cast is untouched.
 */
internal object SignInClickFingerprint : Fingerprint(
    name = "onClick",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        checkCast("Lbrjx;"),
        methodCall(
            definingClass = "Lbrjx;",
            name = "performClick",
            opcode = Opcode.INVOKE_VIRTUAL,
            location = MatchAfterImmediately(),
        ),
        opcode(Opcode.RETURN_VOID, MatchAfterImmediately()),
    ),
)
