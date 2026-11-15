package org.ungoogled.patches.maps.location

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

internal val networkLocationFallbackPatch = bytecodePatch(
    description = "Keeps the network (Wi-Fi/cell) location provider registered when no fused " +
        "location provider answers, instead of GPS-only, so a fix does not go stale indoors.",
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        val owner = GpsOnlyModeFingerprint.method.definingClass
        fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
        fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference

        // The listener fields, by the provider name each listener is made with.
        val ctor = PlatformLocationStrategyFingerprint.method.implementation!!.instructions.toList()
        fun listener(provider: String): FieldReference {
            val named = ctor.indexOfFirst { (it.opcode == Opcode.CONST_STRING) && ((it as ReferenceInstruction).reference as StringReference).string == provider }
            if (named < 0) throw PatchException("the \"$provider\" location listener is no longer made")
            return ctor.drop(named).firstNotNullOfOrNull { i ->
                i.field()?.takeIf { i.opcode == Opcode.IPUT_OBJECT && it.definingClass == owner }
            } ?: throw PatchException("the \"$provider\" location listener is not kept in a field")
        }
        val gps = listener("gps")
        val network = listener("network")

        // The GPS-only branch: start gps (gps.start(looper)), then stop network (network.stop()).
        GpsOnlyModeFingerprint.method.apply {
            val ins = implementation!!.instructions.toList()
            val at = (0 until ins.size - 4).filter { i ->
                ins[i].field() == gps && ins[i + 1].field()?.type == "Landroid/os/Looper;" &&
                    ins[i + 2].opcode == Opcode.INVOKE_VIRTUAL && ins[i + 2].call()?.parameterTypes?.map { it.toString() } == listOf("Landroid/os/Looper;") &&
                    ins[i + 3].field() == network &&
                    ins[i + 4].opcode == Opcode.INVOKE_VIRTUAL && ins[i + 4].call()?.parameterTypes?.isEmpty() == true &&
                    ins[i + 4].call()?.definingClass == ins[i + 2].call()?.definingClass
            }.singleOrNull() ?: throw PatchException("the GPS-only location mode not found")
            val looper = ins[at + 1].field()!!
            val looperReg = (ins[at + 1] as OneRegisterInstruction).registerA
            val netReg = (ins[at + 3] as OneRegisterInstruction).registerA
            val start = ins[at + 2].call()!!
            val stop = ins[at + 4] as Instruction35c
            if (stop.registerC != netReg || looperReg == netReg || looperReg > 15 || netReg > 15) {
                throw PatchException("the GPS-only location mode changed shape")
            }
            if (ins[at + 4].location.labels.isNotEmpty()) throw PatchException("the network listener's stop is a branch target")
            // Swap the network listener's stop for a start, on the looper the GPS start just used (re-read).
            removeInstructions(at + 4, 1)
            addInstructions(
                at + 4,
                """
                    iget-object v$looperReg, p0, $owner->${looper.name}:Landroid/os/Looper;
                    invoke-virtual { v$netReg, v$looperReg }, ${start.definingClass}->${start.name}(Landroid/os/Looper;)V
                """,
            )
        }
    }
}
