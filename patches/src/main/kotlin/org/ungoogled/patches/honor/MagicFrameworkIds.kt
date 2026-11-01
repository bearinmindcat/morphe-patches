package org.ungoogled.patches.honor

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
import org.ungoogled.patches.samsung.redirectCalls

/** androidhnext's resource ids: package 0x02, a type (attr 0x01, drawable, colour, style, dimen, id, ...), an entry. */
private fun isMagicFrameworkId(value: Int) = value ushr 24 == 0x02 && (value ushr 16 and 0xff) in 0x01..0x20

/** Type 0x01 of androidhnext holds its theme attributes. */
private fun isMagicAttribute(value: Int) = value ushr 16 == 0x0201

private val CONST = setOf(Opcode.CONST, Opcode.CONST_HIGH16)

private fun Instruction.magicLiteral(): Int? =
    if (opcode in CONST && this is NarrowLiteralInstruction && isMagicFrameworkId(narrowLiteral)) narrowLiteral else null

/**
 * Off MagicOS, code that names one of MagicOS's framework resources by id (the androidhnext package, 0x02):
 * `getColor(0x02050177)`, `new ContextThemeWrapper(context, Theme.Magic)`, `setNavigationIcon(ic_public_back)`, or a
 * theme attribute it resolves (`resolveAttribute(?maxPaddingStart)`), finds nothing: Resources$NotFoundException, or an
 * empty answer. The XML side is [replaceMagicFrameworkReferences]; this rewrites the literals in the app's code (const
 * instructions, not our extensions) to the port's [answers], keyed by framework id:
 * - "type/name": one of the app's own resources (Honor's bundled UI kit carries most of MagicOS's names, with their
 *   night variants), for a theme attribute the resource MagicOS's theme answers with;
 * - "0x..": another id, e.g. one of Android's public resources (`Theme.DeviceDefault.DayNight` for `Theme.Magic`);
 * - "": left as it is (a view id used as a tag key, a code path that never runs off MagicOS).
 * A framework id neither answers stops the patch, listed. When a theme attribute was rewritten, the app's
 * Theme.resolveAttribute calls go through [runtime]'s `resolveAttribute` (the honor-standins Magic class), which answers
 * such a resource itself. Returns the literals rewritten, per framework id.
 */
internal fun BytecodePatchContext.replaceMagicFrameworkIds(runtime: String, answers: Map<Int, String>): Map<Int, Int> {
    val resolved = mutableMapOf<Int, Int?>()
    fun answer(id: Int): Int? = resolved.getOrPut(id) {
        val to = answers[id] ?: throw PatchException("no answer for MagicOS framework id 0x%08x".format(id))
        when {
            to.isEmpty() -> null
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
            val ids = m.implementation?.instructions?.mapNotNull { it.magicLiteral() } ?: continue
            if (ids.isEmpty()) continue
            ids.filterNot { it in answers }.forEach { unknown += "0x%08x (%s->%s)".format(it, c.type, m.name) }
            sites += c.type to m
        }
    }
    if (unknown.isNotEmpty()) throw PatchException("MagicOS framework ids in code without an answer: ${unknown.joinToString()}")

    val replaced = sortedMapOf<Int, Int>()
    var attributes = false
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val id = instructions[index].magicLiteral() ?: continue
            val to = answer(id) ?: continue
            mutable.replaceInstruction(index, "const v${(instructions[index] as OneRegisterInstruction).registerA}, $to")
            replaced.merge(id, 1, Int::plus)
            if (isMagicAttribute(id)) attributes = true
        }
    }
    if (attributes) {
        redirectCalls(runtime, mapOf("Landroid/content/res/Resources\$Theme;" to setOf("resolveAttribute(ILandroid/util/TypedValue;Z)Z")))
    }
    return replaced
}

/**
 * Code can also look MagicOS's framework interpolators up by name (`getIdentifier("cubic_bezier_interpolator_type_20_80",
 * "interpolator", "androidhnext")`): off MagicOS that answers 0, and loading interpolator 0 throws (Clock died opening
 * its alarm list). Sends the app's Resources.getIdentifier calls through [runtime]'s `getIdentifier` (the
 * honor-standins Magic class), which answers such a lookup with the app's own interpolator of that name, or Android's
 * nearest; other framework lookups keep answering 0 (Honor's libraries use them to tell MagicOS apart). Returns the
 * calls moved.
 */
internal fun BytecodePatchContext.redirectMagicFrameworkLookups(runtime: String): Int = redirectCalls(
    runtime,
    mapOf("Landroid/content/res/Resources;" to setOf("getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I")),
)
