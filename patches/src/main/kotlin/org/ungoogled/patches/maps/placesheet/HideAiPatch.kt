package org.ungoogled.patches.maps.placesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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

/** The place-page Ask Maps action, the only code that names its "Ask Maps chip". */
private object AskMapsSlotFingerprint : Fingerprint(
    filters = listOf(string("Ask Maps chip")),
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
    description = "Hides Gemini's AI summaries: the \"Know before you go\" card on place sheets " +
        "(also the signed-in one, with its Ask Maps chips) and the review summary (\"Summarized " +
        "with Gemini\") on the Reviews tab. Can be switched off on the Customization screen.",
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
        val cardType = kbyg.returnType

        // Signed in, "Know before you go" comes as a server-driven place module: the
        // place data carries a map of Elements cards by slot, and the module for the
        // Ask Maps slot (the slot whose presence also turns on the Ask Maps place
        // action) renders it at the top of the Overview tab, Ask Maps chips included.
        // None of the getters above serve it. The module fills itself in its one
        // placemark setter; when the switch is on, empty the module there instead, the
        // same state it is in for a place without that slot.
        val askActionType = AskMapsSlotFingerprint.method.definingClass
        val askAction = mutableClassDefBy(askActionType).methods.singleOrNull { m ->
            m.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "containsKey"
            } == true
        } ?: throw PatchException("Ask Maps slot lookup not found in $askActionType")
        val askInsns = askAction.implementation!!.instructions.toList()
        val slotRead = askInsns.indices.firstOrNull { i ->
            i + 3 < askInsns.size && askInsns[i].opcode in listOf(Opcode.CONST_4, Opcode.CONST_16) &&
                askInsns[i + 1].opcode == Opcode.INVOKE_STATIC &&
                ((askInsns[i + 1] as ReferenceInstruction).reference as MethodReference).name == "valueOf" &&
                askInsns[i + 2].opcode == Opcode.MOVE_RESULT_OBJECT &&
                ((askInsns[i + 3] as? ReferenceInstruction)?.reference as? MethodReference)?.name == "containsKey"
        } ?: throw PatchException("Ask Maps slot lookup not found in ${askAction.definingClass}")
        val askSlot = (askInsns[slotRead] as NarrowLiteralInstruction).narrowLiteral
        if (askSlot !in 1..127) throw PatchException("Ask Maps slot $askSlot out of range")

        val holderGetterType = mutableClassDefBy(cardType).methods.singleOrNull { m ->
            m.parameterTypes.isEmpty() && m.returnType.startsWith("L") && !m.returnType.startsWith("Ljava/") &&
                m.returnType != "[B" && m.name == "a"
        }?.returnType ?: throw PatchException("card holder getter not found on $cardType")
        val modules = mutableListOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/") || cardType !in classDef.interfaces) return@classDefForEach
            if (classDef.fields.none { it.type == "I" && it.accessFlags and 0x8 == 0 }) return@classDefForEach
            if (classDef.methods.any { m ->
                    m.parameterTypes.size == 1 && m.returnType == "V" && m.implementation?.instructions?.any {
                        it.opcode == Opcode.INVOKE_INTERFACE &&
                            ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                                r.definingClass == "Ljava/util/Map;" && r.name == "get"
                            }
                    } == true
                }) modules += classDef.type
        }
        val moduleType = modules.singleOrNull()
            ?: throw PatchException("expected one server-driven place module, found ${modules.size}: $modules")
        val module = mutableClassDefBy(moduleType)
        val slotField = module.fields.filter { it.type == "I" && it.accessFlags and 0x8 == 0 }.singleOrNull()
            ?: throw PatchException("server-driven place module has no single slot field")
        val fill = module.methods.single { m ->
            m.parameterTypes.size == 1 && m.returnType == "V" && m.implementation?.instructions?.any {
                it.opcode == Opcode.INVOKE_INTERFACE &&
                    ((it as ReferenceInstruction).reference as MethodReference).name == "get"
            } == true
        }
        val clear = module.methods.singleOrNull { m ->
            m.parameterTypes.isEmpty() && m.returnType == "V" && m.name != "<init>" &&
                m.implementation?.instructions?.any {
                    it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).type == holderGetterType
                } == true
        } ?: throw PatchException("server-driven place module has no clear method")
        if (fill.implementation!!.registerCount - fill.parameterTypes.size - 1 < 1) {
            throw PatchException("server-driven place module setter has no local register")
        }
        // v0 is free on entry: nothing can read a local before the method writes it.
        val fillFirst = fill.implementation!!.instructions.first()
        fill.addInstructionsWithLabels(
            0,
            """
                iget v0, p0, ${moduleType}->${slotField.name}:I
                add-int/lit8 v0, v0, -$askSlot
                if-nez v0, :fill_module
                sget-boolean v0, $SHAPES->HIDE_AI:Z
                if-eqz v0, :fill_module
                invoke-virtual {p0}, ${moduleType}->${clear.name}()V
                return-void
            """,
            ExternalLabel("fill_module", fillFirst),
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
