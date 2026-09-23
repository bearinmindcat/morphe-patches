package org.ungoogled.patches.maps.account.trim

import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/**
 * Every row before its own call is set up identically: `move-object vN, v6`
 * then `check-cast vN, Lolr;`. Cutting a span whose end anchor is the NEXT
 * kept row's own call must therefore stop two instructions short of that
 * call, so the next row's own (identical-looking, but unique BY POSITION
 * since it is the one right before its own distinctly-named call) setup
 * survives.
 */
private const val ROW_SETUP_LENGTH = 2

/**
 * Removes [start, end) and checks, before touching anything, that the span is
 * made of WHOLE rows: the instruction in front of it finishes a row (its list
 * add) or builds the list, and the span's own last instruction is a row's list
 * add. A cut that ends one row short would otherwise leave that row's
 * move-result behind with no call in front of it -- which the dex verifier
 * rejects, taking the whole builder class (and Maps' startup) with it. An
 * earlier version of this patch shipped exactly that in the legacy builder,
 * where rows have no setup instructions and ROW_SETUP_LENGTH does not apply.
 */
private fun MutableMethod.removeWholeRows(start: Int, end: Int, what: String) {
    if (end <= start) throw PatchException("$what span is empty or inverted ($start..$end)")
    val instructions = implementation!!.instructions
    fun call(i: Int) = ((instructions[i] as? ReferenceInstruction)?.reference as? MethodReference)
    fun isRowAdd(i: Int) = call(i)?.let { it.definingClass == "Lbwxy;" && it.name == "i" } == true
    fun isListCtor(i: Int) = call(i)?.let { it.definingClass == "Lbwxy;" && it.name == "<init>" } == true
    if (!isRowAdd(start - 1) && !isListCtor(start - 1)) throw PatchException("$what span does not start at a row boundary")
    if (!isRowAdd(end - 1)) throw PatchException("$what span does not end with a whole row")
    removeInstructions(start, end - start)
}

@Suppress("unused")
val trimAccountMenuPatch = bytecodePatch(
    name = "Trim account menu",
    description = "Removes Your Timeline, Location sharing, Your data in Maps and Help & feedback " +
        "from the account sheet.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        // Modern builder: three independent cuts, each a pure removal -- the
        // rows on both sides of every cut are left completely alone, so
        // nothing needs to be reconstructed.
        CustomActionsListFingerprint.let { fp ->
            val m = fp.instructionMatches
            val start = m.first().index + 1
            val end = m.last().index - ROW_SETUP_LENGTH
            fp.method.removeWholeRows(start, end, "custom-actions")
        }
        CommonActionsListFingerprint.let { fp ->
            val m = fp.instructionMatches
            val start = m[1].index + 1 // right after the list's OWN constructor call, m[0]
            val end = m.last().index - ROW_SETUP_LENGTH
            fp.method.removeWholeRows(start, end, "common-actions")
        }
        SettingsRowAddFingerprint.let { fp ->
            val m = fp.instructionMatches
            val start = m[2].index + 1 // right after Settings' own row-add
            val end = m.last().index   // right at the list finalize
            fp.method.removeWholeRows(start, end, "Help & feedback")
        }

        // Legacy builder: neither span references the ambiguous constructor
        // call, so ordering between these two and the modern builder's three
        // above does not matter -- each computes its own indices fresh, in
        // its own class's method, right before applying its own mutation.
        LegacySettingsRowAddFingerprint.let { fp ->
            val m = fp.instructionMatches
            val start = m[2].index + 1 // right after Settings' own row-add
            val end = m.last().index   // right at the list finalize
            fp.method.removeWholeRows(start, end, "legacy Settings")
        }
        LegacyYourProfileFingerprint.let { fp ->
            val m = fp.instructionMatches
            val start = m[2].index + 1 // right after Your profile's own row-add
            // Legacy rows call straight through a typed register -- no setup
            // instructions -- so the kept row starts AT its own call.
            val end = m.last().index
            fp.method.removeWholeRows(start, end, "legacy Your-profile")
        }
    }
}
