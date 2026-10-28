package org.ungoogled.patches.maps.placesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
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

/** ASKMAPS_ENTRYPOINT_LABEL, "Ask Maps": only the Ask Maps chip under the search bar shows it. */
private const val ASK_MAPS_CHIP_LABEL = 0x7f140320

/** The Ask Maps chip's label getter; its class is the chip. */
private object AskMapsChipFingerprint : Fingerprint(
    filters = listOf(literal(ASK_MAPS_CHIP_LABEL)),
)

/** Whether Ask Maps (Gemini) runs during navigation: its button on the navigation sheet and its voice session. */
private object AskMapsInNavigationFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(string("AskMapsNavigationFeatureAvailabilityImpl.isAskMapsInNavigationEnabled")),
)

/** SEARCH_LIST_NAVIGATION_HEADER_ASK_MAPS_MODE_BUTTON: loaded right after the "is Ask Maps on" checks. */
private const val ASK_MAPS_SEARCH_HEADER_BUTTON = 0x7f141d38

/** ASK_MAPS_PLACE_ACTION_TEXT, "Ask": the place sheet's Ask action. */
private const val ASK_MAPS_PLACE_ACTION = 0x7f140373

/** ASK_MAPS_LIST_ACTION_TEXT, "Ask": the saved list's Ask action. */
private const val ASK_MAPS_LIST_ACTION = 0x7f14035c

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
        "review summary (\"Summarized with Gemini\") on the Reviews tab, and Ask Maps: its chips, its Ask buttons and its " +
        "button in navigation. Can be switched off on the Customization screen.",
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

        // Ask Maps' chip under the search bar (issues #68, #82). Every chip row asks one factory for it and adds
        // it only when it got one -- null is what a phone without Ask Maps gets -- so the factory answers null.
        val chip = AskMapsChipFingerprint.originalMethod.definingClass
        val factories = mutableListOf<Pair<String, Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                if (method.returnType != chip) continue
                val builds = method.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.NEW_INSTANCE && ((insn as ReferenceInstruction).reference as TypeReference).type == chip
                } == true
                if (builds) factories += classDef.type to method
            }
        }
        val (owner, built) = factories.singleOrNull()
            ?: throw PatchException("expected one Ask Maps chip factory, found ${factories.size}")
        val factory = mutableClassDefBy(owner).methods.single {
            it.name == built.name && it.parameterTypes == built.parameterTypes && it.returnType == chip
        }
        // v0 is a local: the factory takes three arguments and has more registers than those and `this`.
        if (factory.implementation!!.registerCount - factory.parameterTypes.size - 1 < 1) throw PatchException("Ask Maps chip factory has no local register")
        factory.addInstructionsWithLabels(
            0,
            """
                sget-boolean v0, $SHAPES->HIDE_AI:Z
                if-eqz v0, :ask_maps_chip
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("ask_maps_chip", factory.implementation!!.instructions.first()),
        )

        fun Method.literalAt(id: Int) = implementation?.instructions?.indexOfFirst {
            (it as? NarrowLiteralInstruction)?.narrowLiteral == id
        } ?: -1

        // Ask Maps as a whole (issue #82). Maps asks one server switch whether Ask Maps exists at all -- the
        // home screen's shortcut chip ("Ask Maps", first under the search bar), the search list's Ask Maps
        // button, "no results? Ask Maps", Ask Maps mode, its notifications and links, and the requests that
        // tell Google this client supports it -- and Google turns it on some time after install, which is why
        // Hide AI seemed to stop working. It answers no, as where Google has not launched Ask Maps. The switch
        // is the one the search list checks (through a second one that also needs it) right before loading its
        // Ask Maps button label.
        run {
            var header: Pair<String, Method>? = null
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                c.methods.firstOrNull { it.literalAt(ASK_MAPS_SEARCH_HEADER_BUTTON) >= 0 }?.let { header = c.type to it }
            }
            val (_, headerMethod) = header ?: throw PatchException("Ask Maps search header button not found")
            val headerInsns = headerMethod.implementation!!.instructions.toList()
            val at = headerMethod.literalAt(ASK_MAPS_SEARCH_HEADER_BUTTON)
            fun boolCall(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                    insn.opcode == Opcode.INVOKE_VIRTUAL && it.returnType == "Z" && it.parameterTypes.isEmpty()
                }
            val outer = (at - 1 downTo 0).firstNotNullOfOrNull { boolCall(headerInsns[it]) }
                ?: throw PatchException("no Ask Maps check in front of the search header button")
            val outerMethod = classDefBy(outer.definingClass).methods.single {
                it.name == outer.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
            }
            val master = outerMethod.implementation!!.instructions.firstNotNullOfOrNull { insn ->
                boolCall(insn)?.takeIf { it.definingClass == outer.definingClass }
            } ?: throw PatchException("${outer.definingClass}.${outer.name}() no longer builds on the Ask Maps switch")
            val enabled = mutableClassDefBy(master.definingClass).methods.single {
                it.name == master.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
            }
            // Its only register may be `this`, so the answer is changed on its way out: false stays false,
            // true becomes !HIDE_AI. Every return, from the last up, so no index moves under the next.
            val returns = enabled.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN }.map { it.index }.reversed()
            if (returns.isEmpty()) throw PatchException("Ask Maps switch has no return")
            for (r in returns) {
                val ret = enabled.implementation!!.instructions[r]
                val reg = (ret as OneRegisterInstruction).registerA
                enabled.addInstructionsWithLabels(
                    r,
                    """
                        if-eqz v$reg, :ask_maps_off
                        sget-boolean v$reg, $SHAPES->HIDE_AI:Z
                        xor-int/lit8 v$reg, v$reg, 0x1
                    """,
                    ExternalLabel("ask_maps_off", ret),
                )
            }
        }

        // The place sheet's "Ask" action ("Pergunte" in Portuguese, issue #82): shown when the place's data
        // offers Ask Maps for it, whatever the switch above says; its "show it?" answers no.
        run {
            var owner: String? = null
            classDefForEach { c ->
                if (owner == null && c.methods.any { it.literalAt(ASK_MAPS_PLACE_ACTION) >= 0 }) owner = c.type
            }
            val show = mutableClassDefBy(owner ?: throw PatchException("place sheet Ask action not found")).methods.singleOrNull {
                it.name == "c" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Boolean;"
            } ?: throw PatchException("place sheet Ask action has no visibility answer")
            // It reads its flag into its only register, `this`, then boxes it: the flag is changed in between,
            // false stays false, true becomes !HIDE_AI.
            val read = show.implementation!!.instructions.indexOfFirst { it.opcode == Opcode.IGET_BOOLEAN }
            val box = show.implementation!!.instructions.getOrNull(read + 1)
            if (read < 0 || box?.opcode != Opcode.INVOKE_STATIC) throw PatchException("place sheet Ask action's answer has an unexpected shape")
            val flag = (show.implementation!!.instructions[read] as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA
            show.addInstructionsWithLabels(
                read + 1,
                """
                    if-eqz v$flag, :place_ask
                    sget-boolean v$flag, $SHAPES->HIDE_AI:Z
                    xor-int/lit8 v$flag, v$flag, 0x1
                """,
                ExternalLabel("place_ask", box),
            )
        }

        // A saved list's "Ask" action: added after its own server check; skipped the way that check skips it.
        run {
            var field: FieldReference? = null
            classDefForEach { c ->
                if (field != null) return@classDefForEach
                val init = c.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
                val at = init.literalAt(ASK_MAPS_LIST_ACTION)
                if (at < 0) return@classDefForEach
                field = init.implementation!!.instructions.drop(at).firstNotNullOfOrNull { insn ->
                    ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { insn.opcode == Opcode.SPUT_OBJECT }
                }
            }
            val action = field ?: throw PatchException("saved list Ask action not found")
            val adds = mutableListOf<Pair<String, Method>>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                for (m in c.methods) {
                    val reads = m.implementation?.instructions?.any { insn ->
                        insn.opcode == Opcode.SGET_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).let {
                            it.definingClass == action.definingClass && it.name == action.name
                        }
                    } == true
                    if (reads) adds += c.type to m
                }
            }
            var gated = 0
            for ((type, found) in adds) {
                val m = mutableClassDefBy(type).methods.first {
                    it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType
                }
                val insns = m.implementation!!.instructions
                val read = insns.indexOfFirst { insn ->
                    insn.opcode == Opcode.SGET_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).let {
                        it.definingClass == action.definingClass && it.name == action.name
                    }
                }
                val made = read - 1
                if (made < 1 || insns[made].opcode != Opcode.NEW_INSTANCE || insns[made - 1].opcode != Opcode.IF_EQZ) continue
                val reg = (insns[made] as OneRegisterInstruction).registerA
                val skip = (m.getInstruction(made - 1) as BuilderInstruction21t).target.location.instruction
                    ?: throw PatchException("saved list Ask action's skip target has no instruction")
                m.addInstructionsWithLabels(
                    made,
                    """
                        sget-boolean v$reg, $SHAPES->HIDE_AI:Z
                        if-nez v$reg, :list_ask_off
                    """,
                    ExternalLabel("list_ask_off", skip),
                )
                gated++
            }
            if (gated == 0) throw PatchException("saved list Ask action is no longer added behind a check")
        }

        // Ask Maps in navigation (issues #17, #68): the navigation sheet's Gemini button and its voice session
        // only start when this says yes, as on a phone the server gives no Ask Maps.
        AskMapsInNavigationFingerprint.method.let { inNavigation ->
            inNavigation.addInstructionsWithLabels(
                0,
                """
                    sget-boolean v0, $SHAPES->HIDE_AI:Z
                    if-eqz v0, :ask_maps_navigation
                    const/4 v0, 0x0
                    return v0
                """,
                ExternalLabel("ask_maps_navigation", inNavigation.implementation!!.instructions.first()),
            )
        }
    }
}
