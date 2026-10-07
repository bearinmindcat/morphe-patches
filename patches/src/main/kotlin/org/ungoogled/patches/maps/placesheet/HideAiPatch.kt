package org.ungoogled.patches.maps.placesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** AI_REVIEW_SUMMARY_DISCLAIMER, "Summarized with Gemini" under the review summary. */
private const val AI_REVIEW_SUMMARY_DISCLAIMER = 0x7f1401c3

/** The review summary's layout, the only code that draws that label. */
private object ReviewSummaryLayoutFingerprint : Fingerprint(
    filters = listOf(literal(AI_REVIEW_SUMMARY_DISCLAIMER)),
)

/**
 * How the header's "Know before you go" getter ends: `return slot` when nothing else
 * fills its spot, else `return null`. The header's other getters of that type are a
 * plain field read and the one that calls this.
 */
private val KBYG_GETTER_TAIL = listOf(
    Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT, Opcode.CONST_4, Opcode.RETURN_OBJECT,
)

/** The review summary's "is there one" check, `return text.length() > 0`. */
private val SUMMARY_CHECK = listOf(
    Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT, Opcode.IF_LEZ,
    Opcode.CONST_4, Opcode.RETURN, Opcode.CONST_4, Opcode.RETURN,
)

internal val hideAiPatch = bytecodePatch(
    description = "Hides Gemini's AI summaries: the \"Know before you go\" card on place sheets and the " +
        "review summary (\"Summarized with Gemini\") on the Reviews tab. Can be switched off on the " +
        "Customization screen.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // HIDE_AI is refreshed from the Customization switch at every Activity attach.
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("hideAiPatched")

        // "Know before you go" is server-driven (Elements) content: the place data carries it in
        // one of its numbered content slots, and the header view model hands that slot to the
        // header layout right under the action buttons. A null is what a place without one gets,
        // so the sheet lays out as it does there.
        val header = mutableClassDefBy(placeSheetHeaderType())
        fun calls(caller: Method, callee: Method) =
            caller.implementation?.instructions?.any { insn ->
                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                ref != null && ref.definingClass == header.type && ref.name == callee.name &&
                    ref.parameterTypes.isEmpty() && ref.returnType == callee.returnType
            } == true
        val kbyg = header.methods.filter { m ->
            m.parameterTypes.isEmpty() && m.returnType.startsWith("L") &&
                m.implementation?.instructions?.map { it.opcode }?.takeLast(KBYG_GETTER_TAIL.size) == KBYG_GETTER_TAIL &&
                header.methods.any { it != m && it.returnType == m.returnType && calls(it, m) }
        }.singleOrNull() ?: throw PatchException("Know before you go getter not found in ${header.type}")
        // v0 is its one local, overwritten by its own first instruction.
        if (kbyg.implementation!!.registerCount < 2) throw PatchException("Know before you go getter has no local register")
        kbyg.addInstructionsWithLabels(
            0,
            """
                sget-boolean v0, $SHAPES->HIDE_AI:Z
                if-eqz v0, :show_ai
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("show_ai", kbyg.implementation!!.instructions.first()),
        )

        // The review summary. Both lists that add it (the Reviews tab and the overview's reviews
        // block) ask its view model "is there one" right before creating its layout; that is the
        // nearest call in front of the layout's new-instance.
        val layout = ReviewSummaryLayoutFingerprint.originalMethod.definingClass
        val gates = mutableSetOf<MethodReference>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                val insns = method.implementation?.instructions?.toList() ?: continue
                insns.forEachIndexed { i, insn ->
                    if (insn.opcode != Opcode.NEW_INSTANCE) return@forEachIndexed
                    if (((insn as ReferenceInstruction).reference as TypeReference).type != layout) return@forEachIndexed
                    val call = insns.subList(0, i).lastOrNull { (it as? ReferenceInstruction)?.reference is MethodReference }
                    val ref = (call as? ReferenceInstruction)?.reference as? MethodReference
                        ?: throw PatchException("no call in front of the review summary layout in ${method.definingClass}")
                    if (ref.parameterTypes.isNotEmpty() || ref.returnType != "Z") {
                        throw PatchException("unexpected call in front of the review summary layout: $ref")
                    }
                    gates += ref
                }
            }
        }
        val gate = gates.singleOrNull()
            ?: throw PatchException("expected one review summary check, found ${gates.size}: $gates")
        // The check is on the view model's interface; the summary's view model is its one implementation.
        val models = mutableListOf<String>()
        classDefForEach { classDef ->
            if (gate.definingClass in classDef.interfaces) models += classDef.type
        }
        val model = models.singleOrNull()
            ?: throw PatchException("expected one review summary view model, found ${models.size}")
        val check = mutableClassDefBy(model).methods.singleOrNull {
            it.name == gate.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
        } ?: throw PatchException("review summary check not found in $model")
        if (check.implementation?.instructions?.map { it.opcode } != SUMMARY_CHECK) {
            throw PatchException("review summary check in $model has an unexpected shape")
        }
        // `if (length > 0) return true;` becomes `return !HIDE_AI`. The method has no spare register,
        // and the one `true` lands in holds only the length by then. No branch moves.
        val yes = check.implementation!!.instructions[4]
        if ((yes as NarrowLiteralInstruction).narrowLiteral != 1) throw PatchException("review summary check does not return true there")
        val register = (yes as OneRegisterInstruction).registerA
        check.replaceInstruction(4, "sget-boolean v$register, $SHAPES->HIDE_AI:Z")
        check.addInstruction(5, "xor-int/lit8 v$register, v$register, 0x1")
    }
}
