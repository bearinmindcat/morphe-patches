package org.ungoogled.patches.maps.microg

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.maps.ui.sharedExtensionPatch

private const val MICROG = "Lorg/ungoogled/ui/MicroG;"
private const val STATUS = "Lcom/google/android/gms/common/api/Status;"
private const val LOCATION_SETTINGS_REQUEST = "Lcom/google/android/gms/location/LocationSettingsRequest;"

/**
 * "Turn on location" (issue #27): with Android's location off, my location has Play services
 * resolve its location check, and MicroG-RE's dialog is under Google's action in its own
 * package, which it does not answer: the ActivityNotFoundException crashed Maps. Every call to
 * Status.startResolutionForResult(Activity, int) goes through MicroG.resolve, which opens
 * Android's location settings instead. The location check itself -- the one class that builds a
 * LocationSettingsRequest -- goes through MicroG.resolveLocation, which also answers it while
 * location is on (issue #30). Both pass everything to Play services as before whenever its
 * screen exists, so Google's own Play services is unaffected.
 *
 * Shared: microG Maps talks to microG for everything, and Ungoogled Maps does for location when
 * its Location source is microg Services (issue #34).
 */
internal val microgLocationDialogPatch = bytecodePatch(
    description = "Keeps MicroG-RE's missing location dialog from crashing Maps or stopping my location.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        fun startsResolution(insn: Instruction) =
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                r.definingClass == STATUS && r.returnType == "V" &&
                    r.parameterTypes.map(CharSequence::toString) == listOf("Landroid/app/Activity;", "I")
            } == true
        val resolutionCallers = mutableListOf<Pair<String, Method>>()
        val locationChecks = mutableSetOf<String>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                val instructions = m.implementation?.instructions ?: continue
                if (instructions.any(::startsResolution)) resolutionCallers += c.type to m
                if (instructions.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                        r.definingClass == LOCATION_SETTINGS_REQUEST && r.name == "<init>" } == true }
                ) locationChecks += c.type
            }
        }
        if (resolutionCallers.isEmpty()) throw PatchException("Status.startResolutionForResult is no longer called")
        if (resolutionCallers.count { it.first in locationChecks } != 1) {
            throw PatchException("expected one location settings check to resolve, found ${resolutionCallers.filter { it.first in locationChecks }}")
        }
        val resolutionNames = mutableSetOf<String>()
        for ((type, found) in resolutionCallers) {
            val method = mutableClassDefBy(type).methods.single {
                it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType
            }
            val ins = method.implementation!!.instructions.toList()
            for (i in ins.indices.reversed()) {
                val insn = ins[i]
                if (!startsResolution(insn)) continue
                resolutionNames += ((insn as ReferenceInstruction).reference as MethodReference).name
                val handler = if (type in locationChecks) "resolveLocation" else "resolve"
                val resolve = "$MICROG->$handler(Ljava/lang/Object;Landroid/app/Activity;I)V"
                when {
                    insn.opcode == Opcode.INVOKE_VIRTUAL && insn is Instruction35c ->
                        method.replaceInstruction(i, "invoke-static { v${insn.registerC}, v${insn.registerD}, v${insn.registerE} }, $resolve")
                    insn.opcode == Opcode.INVOKE_VIRTUAL_RANGE && insn is Instruction3rc ->
                        method.replaceInstruction(i, "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + 2} }, $resolve")
                    else -> throw PatchException("unexpected call to Status.startResolutionForResult in $type: ${insn.opcode}")
                }
            }
        }
        val resolutionName = resolutionNames.singleOrNull()
            ?: throw PatchException("Status.startResolutionForResult has more than one name: $resolutionNames")
        mutableClassDefBy(MICROG).methods.single {
            it.name == "resolveMethod" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        }.apply {
            val first = implementation!!.instructions.first()
            if (first.opcode != Opcode.CONST_STRING) throw PatchException("MicroG.resolveMethod() no longer starts with const-string")
            replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$resolutionName\"")
        }
    }
}
