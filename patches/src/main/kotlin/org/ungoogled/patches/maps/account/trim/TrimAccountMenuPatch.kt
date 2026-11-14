package org.ungoogled.patches.maps.account.trim

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.maps.microg.MicrogSelection
import org.ungoogled.patches.maps.ui.customization.accountSheet
import org.ungoogled.patches.maps.ui.mapsId
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/*
 * Both account sheet builders (see AccountSheet) add every row the same way:
 *
 *     invoke-... row()           // a row method of the holder, or a static factory
 *     move-result-object vRow
 *     invoke-virtual { vList, vRow }, list.add(Object)
 *
 * A row is recognised by what its method loads -- its title string or Google's visual element
 * id, both stable across Maps builds -- and only its add is dropped (made a nop), so no code
 * around it moves and the rows of ours placed after Settings stay as they are.
 */

/** Visual elements Maps logs the two common rows with. */
private const val YOUR_DATA_VE = 90536
private const val HELP_AND_FEEDBACK_VE = 90538

@Suppress("unused")
val trimAccountMenuPatch = bytecodePatch(
    name = "Trim account menu",
    description = "Removes Your Timeline, Location sharing, Your data in Maps and Help & feedback " +
        "from the account sheet. Left out with Add microG-RE support, whose account features need them.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        if (MicrogSelection.replaces(this, "Trim account menu")) return@execute
        val doomed = mapOf(
            "Your Timeline" to mapsId(ResourceType.STRING, "YOUR_TIMELINE"),
            "Location sharing" to mapsId(ResourceType.STRING, "LOCATION_SHARING_FEATURE_TITLE_VARIANT_LOCATION_SHARING"),
            "Your data in Maps" to YOUR_DATA_VE,
            "Help & feedback" to HELP_AND_FEEDBACK_VE,
        )
        val sheet = accountSheet()
        fun MethodReference.literals(): Set<Int> {
            // Rows of ours (ua*) are made like "Your data in Maps" and stay.
            if (name.startsWith("ua")) return emptySet()
            val method = classDefByOrNull(definingClass)?.methods?.singleOrNull {
                it.name == name && it.parameterTypes.map(CharSequence::toString) == parameterTypes.map(CharSequence::toString) &&
                    it.returnType == returnType
            } ?: return emptySet()
            return method.implementation?.instructions?.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }?.toSet().orEmpty()
        }
        for (builder in sheet.builders) {
            val method = builder.method
            val instructions = method.implementation!!.instructions
            val found = mutableMapOf<String, Int>()
            for (i in 2 until instructions.size) {
                val add = instructions[i]
                if (add.opcode != Opcode.INVOKE_VIRTUAL || (add as ReferenceInstruction).reference != sheet.listAdd) continue
                val result = instructions[i - 1]
                val call = instructions[i - 2]
                if (result.opcode != Opcode.MOVE_RESULT_OBJECT || (result as OneRegisterInstruction).registerA != (add as Instruction35c).registerD) continue
                val ref = (call as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                val literals = ref.literals()
                val row = doomed.entries.firstOrNull { it.value in literals }?.key ?: continue
                if (row in found) throw PatchException("$row is added twice in ${method.definingClass}")
                found[row] = i
            }
            val missing = doomed.keys - found.keys
            if (missing.isNotEmpty()) throw PatchException("account sheet rows not found in ${method.definingClass}: ${missing.joinToString()}")
            found.values.forEach { method.replaceInstruction(it, "nop") }
        }
    }
}
