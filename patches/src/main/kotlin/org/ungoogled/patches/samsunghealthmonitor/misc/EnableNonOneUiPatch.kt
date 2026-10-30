package org.ungoogled.patches.samsunghealthmonitor.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsunghealthmonitor.SamsungHealthMonitorConstants.COMPATIBILITY_SAMSUNG_HEALTH_MONITOR
import org.ungoogled.patches.shared.addInstructionsAtLabel

internal const val SAMSUNG_HEALTH_MONITOR_EXTENSION = "extensions/samsunghealthmonitor.mpe"
private const val REGION = "Lorg/ungoogled/samsunghealthmonitor/Region;"

/** Sends Health Monitor's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_HEALTH_MONITOR_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers(
            "Lorg/ungoogled/samsunghealthmonitor/OneUi;", "Lorg/ungoogled/samsunghealthmonitor/Sem;",
        )
        // Measured against 1.5.3.011: 2 calls (pop-over options, removing a blood pressure cuff's bond), no fields.
        java.util.logging.Logger.getLogger("SamsungHealthMonitor").info("One UI members: $calls calls, $fields fields")
        if (calls == 0) throw PatchException("no One UI calls found")
    }
}

/**
 * Off One UI Health Monitor closes the moment it opens: the launcher activity requires a Samsung maker. Past that,
 * every screen runs a rooting check that also fails any copy not signed by Samsung (which a patched app never is),
 * and set-up needs the CSC country (the country the phone was sold for), a One UI property; the SIM's country
 * stands in for it, so the app's own country rules still apply. A stock watch app only trusts Samsung's phone
 * certificate (its wear-trusted-peer-packages), so it talks to this copy only when it is signed with the same key.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Health Monitor usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_HEALTH_MONITOR)
    dependsOn(oneUiMembersPatch)
    extendWith(SAMSUNG_HEALTH_MONITOR_EXTENSION)

    execute {
        // Maker: the launcher reads "samsung" (a replace keeps the branch target on the read).
        LauncherMakerCheckFingerprint.let { fp ->
            val read = fp.instructionMatches[1]
            val register = (fp.method.implementation!!.instructions[read.index] as OneRegisterInstruction).registerA
            fp.method.replaceInstruction(read.index, "const-string v$register, \"samsung\"")
        }

        // Signature: the comparison with Samsung's certificates reads as a match; the root checks before it stay.
        OwnSignatureCheckFingerprint.let { fp ->
            val result = fp.instructionMatches.last().index
            val instructions = fp.method.implementation!!.instructions
            val register = (instructions[result] as OneRegisterInstruction).registerA
            val test = instructions[result + 1]
            // "if-nez match -> not rooted": anything else and a forced match could mean the opposite.
            if (test.opcode != Opcode.IF_NEZ || (test as OneRegisterInstruction).registerA != register) {
                throw PatchException("signature comparison is not followed by its test (${test.opcode})")
            }
            // The test is also where the "no signature" path joins, so insert at its label.
            fp.method.addInstructionsAtLabel(result + 1, "const/16 v$register, 0x1")
        }

        // CSC country: when One UI has none, the SIM's country.
        CscCountryFingerprint.method.let { method ->
            val returns = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
            if (returns.isEmpty()) throw PatchException("CSC country lookup has no return")
            for (index in returns.reversed()) {
                val register = (method.implementation!!.instructions[index] as OneRegisterInstruction).registerA
                method.replaceInstruction(index, "invoke-static/range { v$register .. v$register }, $REGION->csc(Ljava/lang/String;)Ljava/lang/String;")
                method.addInstructions(index + 1, "move-result-object v$register\nreturn-object v$register")
            }
        }
    }
}
