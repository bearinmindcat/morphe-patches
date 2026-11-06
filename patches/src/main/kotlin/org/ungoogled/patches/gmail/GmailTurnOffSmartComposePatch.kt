package org.ungoogled.patches.gmail

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL

/** The account preferences behind Settings > Smart Compose and Smart Reply. */
private val PREFERENCES = setOf("sc_enabled", "sr-enabled")

/**
 * Smart Compose sends the draft being typed to Google's taskassist server (taskassist-pa.googleapis.com, ":compose")
 * for each suggestion; it is not on-device. Both features read one account preference each, default on; those two
 * getters now say off, so the editor never asks for suggestions and no reply suggestions are offered.
 */
@Suppress("unused")
val gmailTurnOffSmartComposePatch = bytecodePatch(
    name = "Turn off Smart Compose",
    description = "Turns off Smart Compose and Smart Reply, so what you type isn't sent to Google for suggestions.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GMAIL)

    execute {
        val users = methodsUsing(PREFERENCES)
        for (key in PREFERENCES) {
            // The getter: a no-argument boolean method reading the key with SharedPreferences.getBoolean.
            val getters = users[key].orEmpty().filter { found ->
                found.method.returnType == "Z" && found.method.parameterTypes.isEmpty() &&
                    found.method.implementation!!.instructions.any { insn ->
                        ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                            it.definingClass == "Landroid/content/SharedPreferences;" && it.name == "getBoolean"
                        } == true
                    }
            }
            val getter = getters.singleOrNull() ?: throw PatchException("expected one getter of \"$key\", found ${getters.size}")
            mutable(getter).apply {
                if (implementation!!.registerCount < 2) throw PatchException("\"$key\" getter has no free register")
                addInstructions(0, "const/4 v0, 0x0\nreturn v0")
            }
        }
    }
}
