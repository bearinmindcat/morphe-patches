package org.ungoogled.patches.samsunghealth.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsunghealth.SamsungHealthConstants.COMPATIBILITY_SAMSUNG_HEALTH

/** Logged by each copy of HsCommonUtils.isHealthSharingAvailable when the maker is not Samsung. */
private const val NOT_SAMSUNG_DEVICE = "isHealthSharingAvailable: Not Samsung Device"

/** The deep-link check (HsFrameworkHService.onCheck) takes one: it decides whether sharing and invitation links open. */
private const val DEEP_LINK_INTENT = "Lcom/samsung/android/app/shealth/deeplink/DeepLinkIntent;"

private fun Method.usesString(value: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
} == true

/**
 * Health data sharing asks HsCommonUtils.isHealthSharingAvailable, which R8 copied into three places: the deep-link
 * check for the sharing screen and invitation links, the My Page tile and the main screen's sharing set-up. Each asks
 * the app-wide maker check (Build.MANUFACTURER contains "samsung") and then whether the Samsung account's country is
 * one sharing serves. Only those maker checks answer yes now; the app-wide check is untouched, so nothing else changes
 * and nothing tells Samsung's servers the phone is a Samsung. The country check stays. Only the invitation link route
 * can work off One UI: the family group route needs the Samsung Account system app.
 */
@Suppress("unused")
val healthSharingPatch = bytecodePatch(
    name = "Health sharing on non-Samsung phones",
    description = "Lets Health data sharing open on non-Samsung phones (experimental, untested with real accounts).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_HEALTH)

    execute {
        // The sharing checks, found by their log line.
        val gates = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            c.methods.filter { it.usesString(NOT_SAMSUNG_DEVICE) }.forEach { gates += c.type to it }
        }
        if (gates.isEmpty()) throw PatchException("no health sharing checks found (\"$NOT_SAMSUNG_DEVICE\")")
        if (gates.none { (_, m) -> DEEP_LINK_INTENT in m.parameterTypes.map(CharSequence::toString) }) {
            throw PatchException("the health sharing deep-link check was not found")
        }

        // The maker check, by its shape: a static ()Z that reads Build.MANUFACTURER and the string "samsung".
        val makerChecks = HashMap<String, Boolean>()
        fun isMakerCheck(ref: MethodReference): Boolean = makerChecks.getOrPut(ref.toString()) {
            if (ref.returnType != "Z" || ref.parameterTypes.isNotEmpty()) return@getOrPut false
            val method = classDefByOrNull(ref.definingClass)?.methods?.firstOrNull {
                it.name == ref.name && it.returnType == "Z" && it.parameterTypes.isEmpty() &&
                    AccessFlags.STATIC.isSet(it.accessFlags)
            } ?: return@getOrPut false
            val instructions = method.implementation?.instructions?.toList() ?: return@getOrPut false
            instructions.any {
                it.opcode == Opcode.SGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).let { f ->
                    f.definingClass == "Landroid/os/Build;" && f.name == "MANUFACTURER"
                }
            } && instructions.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "samsung" }
        }

        var forced = 0
        for ((type, gate) in gates) {
            val method = mutableClassDefBy(type).methods.single {
                it.name == gate.name && it.returnType == gate.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == gate.parameterTypes.map(CharSequence::toString)
            }
            val instructions = method.implementation!!.instructions.toList()
            val results = instructions.withIndex().filter { (_, insn) ->
                insn.opcode == Opcode.INVOKE_STATIC && isMakerCheck((insn as ReferenceInstruction).reference as MethodReference)
            }.map { (index, _) ->
                val result = instructions.getOrNull(index + 1)
                if (result?.opcode != Opcode.MOVE_RESULT) {
                    throw PatchException("maker check in $type->${gate.name} is not followed by its result (${result?.opcode})")
                }
                index + 1
            }
            if (results.isEmpty()) throw PatchException("no maker check in the sharing check $type->${gate.name}")
            // The call stays (it only reads Build.MANUFACTURER); its result reads as "Samsung" here only.
            for (index in results) {
                val register = (instructions[index] as OneRegisterInstruction).registerA
                method.replaceInstruction(index, "const/16 v$register, 0x1")
                forced++
            }
        }
        // Measured against 7.1.3.001 and 7.00.6.011: 3 sharing checks, 5 maker checks.
        java.util.logging.Logger.getLogger("SamsungHealth").info("Health sharing: $forced maker checks in ${gates.size} sharing checks")
    }
}
