package org.ungoogled.patches.lg

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.hasResourceId
import app.morphe.patcher.resource.resourceId
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/** LG's framework resource ids: package 0x02, a type (attr 0x01, drawable, layout, colour, style, dimen, id, ...). */
private fun isLgFrameworkId(value: Int) = value ushr 24 == 0x02 && (value ushr 16 and 0xff) in 0x01..0x20

private val CONST = setOf(Opcode.CONST, Opcode.CONST_HIGH16)

private fun Instruction.lgLiteral(): Int? =
    if (opcode in CONST && this is NarrowLiteralInstruction && isLgFrameworkId(narrowLiteral)) narrowLiteral else null

/**
 * Off LG's ROM, code that names one of LG's framework resources by id (the "com.lge" package, 0x02): a dialog layout
 * it inflates, LG's theme for a ContextThemeWrapper, a list item foreground, finds nothing: Resources$NotFoundException.
 * The XML side is [replaceLgFrameworkReferences]; this rewrites the literals in the app's code (const instructions, not
 * our extensions) to the port's [answers], keyed by framework id:
 * - "type/name": one of the app's own resources;
 * - "new:type/name" / "new:android:type/name": a resource the port adds, or one of Android's non-public ones, looked
 *   up by name at run time through [runtime]'s `id` (the lg-standins LgRes class): an added resource gets its id only
 *   when the APK is built, after the bytecode is patched;
 * - "0x..": another id, e.g. one of Android's public resources;
 * - "": left as it is (a code path that never runs off LG's ROM, or one that catches the missing resource).
 * A framework id neither answers stops the patch, listed. Returns the literals rewritten, per framework id.
 */
internal fun BytecodePatchContext.replaceLgFrameworkIds(runtime: String, answers: Map<Int, String>): Map<Int, Int> {
    val resolved = mutableMapOf<Int, Int?>()
    fun answer(id: Int): Int? = resolved.getOrPut(id) {
        val to = answers[id] ?: throw PatchException("no answer for LG framework id 0x%08x".format(id))
        when {
            to.isEmpty() || to.startsWith("new:") -> null
            to.startsWith("0x") -> to.removePrefix("0x").toLong(16).toInt()
            else -> {
                val type = ResourceType.fromValue(to.substringBefore('/'))
                val name = to.substringAfter('/')
                if (!hasResourceId(type, name)) throw PatchException("the app has no resource $to (answer for 0x%08x)".format(id))
                resourceId(type, name).toInt()
            }
        }
    }

    val sites = mutableListOf<Pair<String, Method>>()
    val unknown = sortedSetOf<String>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            val ids = m.implementation?.instructions?.mapNotNull { it.lgLiteral() } ?: continue
            if (ids.isEmpty()) continue
            ids.filterNot { it in answers }.forEach { unknown += "0x%08x (%s->%s)".format(it, c.type, m.name) }
            sites += c.type to m
        }
    }
    if (unknown.isNotEmpty()) throw PatchException("LG framework ids in code without an answer: ${unknown.joinToString()}")

    val replaced = sortedMapOf<Int, Int>()
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        // Back to front: a run-time lookup becomes three instructions.
        for (index in instructions.indices.reversed()) {
            val id = instructions[index].lgLiteral() ?: continue
            val register = (instructions[index] as OneRegisterInstruction).registerA
            val name = answers.getValue(id)
            if (name.startsWith("new:")) {
                mutable.replaceInstruction(index, "const-string v$register, \"${name.removePrefix("new:")}\"")
                mutable.addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $runtime->id(Ljava/lang/String;)I
                        move-result v$register
                    """,
                )
                replaced.merge(id, 1, Int::plus)
                continue
            }
            val to = answer(id) ?: continue
            mutable.replaceInstruction(index, "const v$register, $to")
            replaced.merge(id, 1, Int::plus)
        }
    }
    return replaced
}
