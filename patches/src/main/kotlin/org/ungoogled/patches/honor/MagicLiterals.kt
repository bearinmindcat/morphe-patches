package org.ungoogled.patches.honor

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.samsung.redirectCalls

/**
 * A literal that names a resource of androidhnext (package 0x02): 0x02TTEEEE with one of its types (attr 01 ...
 * transition 14) and an entry in range (its largest type, drawable, has 4652); other 0x02... numbers are not ids.
 */
private fun magicResource(literal: Int) =
    literal ushr 24 == 0x02 && (literal ushr 16) and 0xff in 0x01..0x20 && literal and 0xffff < 0x2000

private val CONSTS = setOf(Opcode.CONST, Opcode.CONST_HIGH16)

/**
 * Off MagicOS there is no androidhnext package, and an app's code names its resources by id as well as its XML does:
 * MagicOS theme attributes it resolves (`getTheme().resolveAttribute(0x020100ca, ...)` -> NotFoundException "Resource ID
 * #0x0" when it reads the answer), Theme.Magic for a ContextThemeWrapper or setTheme, interpolators, drawables.
 * Rewrites each such literal in the app's code (const instructions) to the port's answer in [answers] -- framework id
 * to the app's own resource (usually the copy of what MagicOS gives that Honor's bundled UI kit ships) or an Android
 * id. Where an attribute is answered with a plain app resource, the app's Theme.resolveAttribute calls go to
 * [magic]'s resolveAttribute (the honor-standins runtime class), which answers it with that resource's value. A
 * literal without an answer stops the patch, listed. Returns the literals replaced, per framework id, and the
 * resolveAttribute calls moved.
 */
internal fun BytecodePatchContext.replaceMagicFrameworkLiterals(answers: Map<Int, Int>, magic: String): Pair<Map<String, Int>, Int> {
    val types = mutableListOf<String>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        if (c.methods.any { m ->
                m.implementation?.instructions?.any { it.opcode in CONSTS && magicResource((it as NarrowLiteralInstruction).narrowLiteral) } == true
            }
        ) {
            types += c.type
        }
    }
    val replaced = sortedMapOf<String, Int>()
    val unknown = sortedSetOf<String>()
    for (type in types) {
        for (method in mutableClassDefBy(type).methods) {
            val instructions = method.implementation?.instructions ?: continue
            for (index in instructions.indices) {
                val insn = instructions[index]
                if (insn.opcode !in CONSTS) continue
                val literal = (insn as NarrowLiteralInstruction).narrowLiteral
                if (!magicResource(literal)) continue
                val key = "0x%08x".format(literal)
                val answer = answers[literal]
                if (answer == null) {
                    unknown += "$key ($type)"
                    continue
                }
                method.replaceInstruction(index, "const v${(insn as OneRegisterInstruction).registerA}, $answer")
                replaced.merge(key, 1, Int::plus)
            }
        }
    }
    if (unknown.isNotEmpty()) throw PatchException("MagicOS framework ids in code without an answer: ${unknown.joinToString()}")
    val moved = redirectCalls(
        magic,
        mapOf("Landroid/content/res/Resources\$Theme;" to setOf("resolveAttribute(ILandroid/util/TypedValue;Z)Z")),
    )
    return replaced to moved
}
