package org.ungoogled.patches.shared

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.toInstructions
import com.android.tools.smali.dexlib2.Opcode

/**
 * Inserts [smali] so it runs on EVERY path that reaches the instruction at
 * [index], including branches and switch cases that jump to a label on it.
 *
 * Plain `addInstructions(index, ...)` does not do that: dexlib2 inserts new
 * locations IN FRONT of the instruction at [index] and leaves any label where
 * it was, on the displaced instruction, so a branch to that label jumps
 * straight past the new code. (Found the hard way: a switch case whose body was
 * replaced that way dispatched to its neighbour's body, and an override placed
 * "at a merge point" ran on only one of the two paths into it.) A replace keeps
 * a location's labels, so the first new instruction goes INTO the labelled
 * location and the displaced instruction is re-emitted after the rest.
 *
 * [smali] must not contain labels of its own; use it for straight-line code.
 */
fun MutableMethod.addInstructionsAtLabel(index: Int, smali: String) {
    val displaced = implementation!!.instructions[index]
    when (displaced.opcode) {
        Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_EXCEPTION ->
            throw PatchException("cannot insert in front of ${displaced.opcode}: it has to stay where it is")
        else -> Unit
    }
    // A label inside the snippet would stay bound to the snippet's own addresses once
    // its instructions are moved into the method -- use addInstructionsWithLabels for that.
    if (smali.lines().any { it.trim().startsWith(":") || Regex(",\\s*:\\w+\\s*$").containsMatchIn(it) }) {
        throw PatchException("addInstructionsAtLabel takes straight-line code only")
    }
    val compiled = smali.toInstructions(this)
    if (compiled.isEmpty()) throw PatchException("nothing to insert")
    replaceInstruction(index, compiled.first())
    addInstructions(index + 1, compiled.drop(1) + displaced)
}

/**
 * Makes a switch case (or any labelled block) run [smali] instead of its own
 * body. [smali] must end by leaving the method (return or throw).
 *
 * Nothing is removed. Removing the old body would merge its labels -- try
 * ranges, internal branches, the case label itself -- into whatever follows,
 * which is the NEXT case; that is how an earlier version of this repo's toast
 * patches sent a tap to the wrong row. The old body is simply left behind the
 * new return as unreachable code, structurally intact.
 */
fun MutableMethod.replaceBlockAtLabel(index: Int, smali: String) {
    val last = smali.trim().lines().last().trim()
    if (!last.startsWith("return") && !last.startsWith("throw")) {
        throw PatchException("replacement for a block must leave the method, ends with: $last")
    }
    addInstructionsAtLabel(index, smali)
}
