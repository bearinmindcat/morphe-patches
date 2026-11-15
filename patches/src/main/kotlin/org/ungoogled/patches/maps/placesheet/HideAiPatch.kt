package org.ungoogled.patches.maps.placesheet

import org.ungoogled.patches.maps.ui.mapsId
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
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
import org.ungoogled.patches.shared.addInstructionsAtLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** org.ungoogled.ui.HideAi: Know before you go as an Overview tab card. */
private const val HIDE_AI = "Lorg/ungoogled/ui/HideAi;"

/** AI_REVIEW_SUMMARY_DISCLAIMER, "Summarized with Gemini" under the review summary. */
private val AI_REVIEW_SUMMARY_DISCLAIMER get() = mapsId(ResourceType.STRING, "AI_REVIEW_SUMMARY_DISCLAIMER")

/** ASKMAPS_ENTRYPOINT_LABEL, "Ask Maps": only the Ask Maps chip under the search bar shows it. */
private val ASK_MAPS_CHIP_LABEL get() = mapsId(ResourceType.STRING, "ASKMAPS_ENTRYPOINT_LABEL")

/** The Ask Maps chip's label getter; its class is the chip. */
private object AskMapsChipFingerprint : Fingerprint(
    filters = listOf(literal({ ASK_MAPS_CHIP_LABEL.toLong() })),
)

/** Whether Ask Maps (Gemini) runs during navigation: its button on the navigation sheet and its voice session. */
private object AskMapsInNavigationFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(string("AskMapsNavigationFeatureAvailabilityImpl.isAskMapsInNavigationEnabled")),
)

/** ASK_MAPS_MODE_ASSISTIVE_SHORTCUT, "Ask Maps": the home screen's shortcut chip, first under the search bar. */
private val ASK_MAPS_SHORTCUT get() = mapsId(ResourceType.STRING, "ASK_MAPS_MODE_ASSISTIVE_SHORTCUT")

/** SEARCH_LIST_NAVIGATION_HEADER_ASK_MAPS_MODE_BUTTON: loaded right after the "is Ask Maps on" checks. */
private val ASK_MAPS_SEARCH_HEADER_BUTTON get() = mapsId(ResourceType.STRING, "SEARCH_LIST_NAVIGATION_HEADER_ASK_MAPS_MODE_BUTTON")

/** EDITORIAL_SUMMARY_DISCLAIMER, "Summarized with AI": under a place's description when Google's AI wrote it. */
private val EDITORIAL_SUMMARY_DISCLAIMER get() = mapsId(ResourceType.STRING, "EDITORIAL_SUMMARY_DISCLAIMER")

/** The icons a server-made place action can carry that mean Gemini: the spark, and the magnifier with a spark. */
private val SPARK_ICON get() = mapsId(ResourceType.DRAWABLE, "gs_spark_vd_theme_24")
private val SEARCH_SPARK_ICON get() = mapsId(ResourceType.DRAWABLE, "gs_search_spark_vd_theme_24")

/** ASK_MAPS_PLACE_ACTION_TEXT, "Ask": the place sheet's Ask action. */
private val ASK_MAPS_PLACE_ACTION get() = mapsId(ResourceType.STRING, "ASK_MAPS_PLACE_ACTION_TEXT")

/** ASK_MAPS_LIST_ACTION_TEXT, "Ask": the saved list's Ask action. */
private val ASK_MAPS_LIST_ACTION get() = mapsId(ResourceType.STRING, "ASK_MAPS_LIST_ACTION_TEXT")

/** The review summary's layout, the only code that draws that label. */
private object ReviewSummaryLayoutFingerprint : Fingerprint(
    filters = listOf(literal({ AI_REVIEW_SUMMARY_DISCLAIMER.toLong() })),
)

/**
 * How the header's "Know before you go" getter ends: `return slot` when nothing else
 * fills its spot, else `return null`. The header's other getters of that type are a
 * plain field read and the one that calls this.
 */
private val KBYG_GETTER_TAIL = listOf(
    Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT, Opcode.CONST_4, Opcode.RETURN_OBJECT,
)

/** The same tail as 26.44 lays it out: `slot` and `null` meet at one return (a goto between them). */
private val KBYG_GETTER_TAIL_MERGED = listOf(Opcode.IGET_OBJECT, Opcode.CONST_4, Opcode.RETURN_OBJECT)

private fun Method.hasKbygTail(): Boolean {
    val opcodes = implementation?.instructions?.map { it.opcode }?.filter { it != Opcode.GOTO && it != Opcode.GOTO_16 && it != Opcode.GOTO_32 }
        ?: return false
    return opcodes.takeLast(KBYG_GETTER_TAIL.size) == KBYG_GETTER_TAIL || opcodes.takeLast(KBYG_GETTER_TAIL_MERGED.size) == KBYG_GETTER_TAIL_MERGED
}

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
                m.hasKbygTail() &&
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

        // Issue #199: "Know before you go" as a card of the Overview tab. That tab is a server-made list of modules,
        // and some modules only show one of the place's content slots, server-built (Elements) cards; the card came
        // as one of them, where the header getter above never sees it. Each such module looks its slot up in the
        // place's slot table and is left out when the slot is missing; the extension makes it missing when it is the
        // slot the place names as Know before you go (the one the header getter shows) or a Know before you go card.
        run<Unit> {
            // The header's Know before you go: its getter returns this field, filled from the place's slot table by
            // `slots.map.get(Integer.valueOf(place.data().slots.config.kbyg))` -- the names the extension follows.
            val getterCode = kbyg.implementation!!.instructions.toList()
            val kbygField = (getterCode.last { it.opcode == Opcode.IGET_OBJECT } as ReferenceInstruction).reference as FieldReference
            var path: String? = null
            var slotTable: FieldReference? = null
            for (m in header.methods) {
                val code = m.implementation?.instructions?.toList() ?: continue
                val store = code.indexOfFirst { insn ->
                    insn.opcode == Opcode.IPUT_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).let {
                        it.definingClass == kbygField.definingClass && it.name == kbygField.name
                    }
                }
                if (store < 0) continue
                fun ref(i: Int) = (code[i] as? ReferenceInstruction)?.reference
                fun MethodReference.isMapGet() = definingClass == "Ljava/util/Map;" && name == "get"
                fun MethodReference.isBoxing() = definingClass == "Ljava/lang/Integer;" && name == "valueOf"
                // The slot id: the last int field read before the store. 26.36 looks it up right here; 26.44 hands
                // it to a helper of the header that does `slots.map.get(Integer.valueOf(id))`.
                val id = (store downTo 0).firstOrNull { code[it].opcode == Opcode.IGET } ?: continue
                val looksUp = (id until store).any { i ->
                    val r = ref(i) as? MethodReference ?: return@any false
                    if (r.isMapGet()) return@any true
                    if (r.definingClass != header.type) return@any false
                    val helper = header.methods.firstOrNull { it.name == r.name && it.parameterTypes == r.parameterTypes && it.returnType == r.returnType }
                    val refs = helper?.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.orEmpty()
                    refs.any { it.isMapGet() } && refs.any { it.isBoxing() }
                } || (id until store).any { (ref(it) as? MethodReference)?.isBoxing() == true }
                if (!looksUp) continue
                val kbygSlot = ref(id) as FieldReference
                val config = (id downTo 0).firstNotNullOfOrNull { i ->
                    (ref(i) as? FieldReference)?.takeIf { code[i].opcode == Opcode.IGET_OBJECT && it.type == kbygSlot.definingClass }
                } ?: continue
                val slots = (id downTo 0).firstNotNullOfOrNull { i ->
                    (ref(i) as? FieldReference)?.takeIf { code[i].opcode == Opcode.IGET_OBJECT && it.type == config.definingClass }
                } ?: continue
                val data = (id downTo 0).firstNotNullOfOrNull { i ->
                    (ref(i) as? MethodReference)?.takeIf { code[i].opcode == Opcode.INVOKE_VIRTUAL && it.returnType == slots.definingClass && it.parameterTypes.isEmpty() }
                } ?: continue
                path = "${data.name};${slots.name};${config.name};${kbygSlot.name}"
                slotTable = slots
            }
            val kbygPath = path ?: throw PatchException("Know before you go slot is no longer read from the place's slot table")
            val getter = mutableClassDefBy(HIDE_AI).methods.single { it.name == "kbygPath" && it.parameterTypes.isEmpty() }
            val first = getter.implementation!!.instructions.first()
            if (first.opcode != Opcode.CONST_STRING) throw PatchException("$HIDE_AI->kbygPath() no longer starts with const-string")
            getter.replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$kbygPath\"")

            // The Overview tab's slot module: `content = (Content) slots.get(Integer.valueOf(this.slot)); if (content
            // == null) return;` while binding a place.
            val modules = mutableListOf<Pair<String, Method>>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                for (m in c.methods) {
                    if (m.returnType != "V" || m.parameterTypes.size != 1) continue
                    val code = m.implementation?.instructions?.toList() ?: continue
                    val get = code.indexOfFirst { insn ->
                        insn.opcode == Opcode.INVOKE_INTERFACE && ((insn as ReferenceInstruction).reference as MethodReference).let {
                            it.definingClass == "Ljava/util/Map;" && it.name == "get"
                        }
                    }
                    if (get < 3 || get + 4 >= code.size) continue
                    val ownSlot = (code[get - 3] as? ReferenceInstruction)?.reference as? FieldReference
                    if (code[get - 3].opcode != Opcode.IGET || ownSlot?.definingClass != c.type) continue
                    // `if (content == null) return`: an if-nez over a return (26.36), or an if-eqz to the method's
                    // final return (26.44)
                    val nullReturns = (code[get + 3].opcode == Opcode.IF_NEZ && code[get + 4].opcode == Opcode.RETURN_VOID) ||
                        (code[get + 3].opcode == Opcode.IF_EQZ && code.last().opcode == Opcode.RETURN_VOID)
                    if (code[get + 1].opcode != Opcode.MOVE_RESULT_OBJECT || code[get + 2].opcode != Opcode.CHECK_CAST || !nullReturns) continue
                    // looked up in the place's slot table (other modules look up other tables the same way)
                    val readsSlots = code.take(get).any { insn ->
                        insn.opcode == Opcode.IGET_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).let {
                            it.definingClass == slotTable!!.definingClass && it.name == slotTable!!.name
                        }
                    }
                    if (!readsSlots) continue
                    modules += c.type to m
                }
            }
            val (moduleType, found) = modules.singleOrNull()
                ?: throw PatchException("Overview tab slot module: found ${modules.size}")
            val bind = mutableClassDefBy(moduleType).methods.single {
                it.name == found.name && it.returnType == "V" && it.parameterTypes == found.parameterTypes
            }
            val code = bind.implementation!!.instructions.toList()
            val get = code.indexOfFirst { insn ->
                insn.opcode == Opcode.INVOKE_INTERFACE && ((insn as ReferenceInstruction).reference as MethodReference).name == "get"
            }
            val slotField = (code[get - 3] as ReferenceInstruction).reference as FieldReference
            val key = (code[get] as com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c).registerD
            val content = (code[get + 2] as OneRegisterInstruction).registerA
            val contentType = ((code[get + 2] as ReferenceInstruction).reference as TypeReference).type
            val place = bind.implementation!!.registerCount - 1
            if (bind.implementation!!.instructions[get + 3].location.labels.isNotEmpty()) {
                throw PatchException("Overview tab slot module's null check is a branch target")
            }
            if (key > 15 || content > 15 || place > 15 || key == content) throw PatchException("Overview tab slot module registers out of reach")
            if (code.take(get + 3).any { it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == place }) {
                throw PatchException("Overview tab slot module no longer keeps its place in its parameter")
            }
            // Right before `if (content == null) return`: the key's register is free once the lookup is done -- its next
            // use writes it.
            val nextUse = code.drop(get + 4).firstOrNull { insn ->
                when (insn) {
                    is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c ->
                        key in listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG).take(insn.registerCount)
                    is com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction -> insn.registerA == key || insn.registerB == key
                    is OneRegisterInstruction -> insn.registerA == key
                    else -> false
                }
            }
            if (nextUse == null || !nextUse.opcode.setsRegister() || (nextUse as? OneRegisterInstruction)?.registerA != key ||
                (nextUse is com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction && nextUse.registerB == key)
            ) throw PatchException("Overview tab slot module still needs its lookup key")
            bind.addInstructions(
                get + 3,
                """
                    iget v$key, p0, $moduleType->${slotField.name}:I
                    invoke-static { v$content, v$key, v$place }, $HIDE_AI->slot(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$content
                    check-cast v$content, $contentType
                """,
            )
        }

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
        // (26.44 joins the two returns: `true; goto; false; return`)
        val shape = check.implementation?.instructions?.map { it.opcode }
        if (shape != SUMMARY_CHECK && shape != SUMMARY_CHECK.take(5) + listOf(Opcode.GOTO, Opcode.CONST_4, Opcode.RETURN)) {
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

        // The home screen's Ask Maps shortcut chip. Since 26.43 it has a server switch of its own, not the Ask Maps
        // one below: the chip shows while `flags.shortcut()` says so, asked through one getter, `return
        // this.flags.x()`. That answer goes through HIDE_AI (false stays false, true becomes !HIDE_AI).
        run {
            val shortcut = ASK_MAPS_SHORTCUT
            val owners = mutableListOf<String>()
            classDefForEach { c ->
                if (!c.type.startsWith("Lorg/ungoogled/") && c.methods.any { it.literalAt(shortcut) >= 0 }) owners += c.type
            }
            val owner = mutableClassDefBy(owners.singleOrNull() ?: throw PatchException("Ask Maps shortcut chip: found ${owners.size}"))
            val gates = owner.methods.filter { m ->
                val code = m.implementation?.instructions?.toList() ?: return@filter false
                m.returnType == "Z" && m.parameterTypes.isEmpty() &&
                    code.map { it.opcode } == listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.RETURN) &&
                    ((code[0] as ReferenceInstruction).reference as FieldReference).definingClass == owner.type
            }
            val gate = gates.singleOrNull() ?: throw PatchException("Ask Maps shortcut chip's switch: found ${gates.size}")
            val ret = gate.implementation!!.instructions.last()
            val r = (ret as OneRegisterInstruction).registerA
            gate.addInstructionsWithLabels(
                3,
                """
                    if-eqz v$r, :shortcut_off
                    sget-boolean v$r, $SHAPES->HIDE_AI:Z
                    xor-int/lit8 v$r, v$r, 0x1
                """,
                ExternalLabel("shortcut_off", ret),
            )

            // Issue #203: that switch only drives the chip's highlight. The chip itself is put in the home chip
            // row's list either way: with Ask Maps on, or -- since 26.43 -- by a server flag of its own while Ask
            // Maps is off, which is how it came back. Every chip row's list (a method returning a List that casts
            // to the chips' interface) goes through a filter that leaves the shortcut out while HIDE_AI is on.
            // The chip's interface and the chip row item interface it extends (not further up: that is every view model).
            val chipTypes = owner.interfaces.flatMap { listOf(it) + classDefByOrNull(it)?.interfaces.orEmpty() }.toSet()
            val rows = mutableListOf<Pair<String, Method>>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                for (m in c.methods) {
                    if (m.returnType != "Ljava/util/List;" || m.parameterTypes.isNotEmpty()) continue
                    val casts = m.implementation?.instructions?.any { insn ->
                        insn.opcode == Opcode.CHECK_CAST && ((insn as ReferenceInstruction).reference as TypeReference).type in chipTypes
                    } == true
                    if (casts) rows += c.type to m
                }
            }
            if (rows.isEmpty()) throw PatchException("no chip row lists the Ask Maps shortcut chip's kind")
            owner.methods.add(
                ImmutableMethod(
                    owner.type, "uaDropShortcut", listOf(ImmutableMethodParameter("Ljava/util/List;", null, null)), "Ljava/util/List;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(5),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            sget-boolean v0, $SHAPES->HIDE_AI:Z
                            if-eqz v0, :keep
                            if-eqz p0, :keep
                            new-instance v1, Ljava/util/ArrayList;
                            invoke-direct { v1 }, Ljava/util/ArrayList;-><init>()V
                            invoke-interface { p0 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                            move-result-object v2
                            :loop
                            invoke-interface { v2 }, Ljava/util/Iterator;->hasNext()Z
                            move-result v0
                            if-eqz v0, :done
                            invoke-interface { v2 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                            move-result-object v3
                            instance-of v0, v3, ${owner.type}
                            if-nez v0, :loop
                            invoke-interface { v1, v3 }, Ljava/util/List;->add(Ljava/lang/Object;)Z
                            goto :loop
                            :done
                            invoke-static { v1 }, Lcom/google/common/collect/ImmutableList;->copyOf(Ljava/util/Collection;)Lcom/google/common/collect/ImmutableList;
                            move-result-object v1
                            return-object v1
                            :keep
                            return-object p0
                        """,
                    )
                },
            )
            for ((type, found) in rows) {
                val m = mutableClassDefBy(type).methods.single { it.name == found.name && it.parameterTypes.isEmpty() && it.returnType == found.returnType }
                val returns = m.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
                for (r in returns.reversed()) {
                    val reg = (m.implementation!!.instructions[r] as OneRegisterInstruction).registerA
                    m.addInstructionsAtLabel(
                        r,
                        """
                            invoke-static/range { v$reg .. v$reg }, ${owner.type}->uaDropShortcut(Ljava/util/List;)Ljava/util/List;
                            move-result-object v$reg
                        """,
                    )
                }
            }
        }

        // Ask Maps as a whole (issue #82). Maps asks one server switch whether Ask Maps exists at all -- the
        // home screen's shortcut chip ("Ask Maps", first under the search bar), the search list's Ask Maps
        // button, "no results? Ask Maps", Ask Maps mode, its notifications and links, and the requests that
        // tell Google this client supports it -- and Google turns it on some time after install, which is why
        // Hide AI seemed to stop working. It answers no, as where Google has not launched Ask Maps. The switch
        // is the one the search list checks (through a second one that also needs it) right before loading its
        // Ask Maps button label.
        run {
            var header: Pair<String, Method>? = null
            val headerButton = ASK_MAPS_SEARCH_HEADER_BUTTON
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                c.methods.firstOrNull { it.literalAt(headerButton) >= 0 }?.let { header = c.type to it }
            }
            val (_, headerMethod) = header ?: throw PatchException("Ask Maps search header button not found")
            val headerInsns = headerMethod.implementation!!.instructions.toList()
            val at = headerMethod.literalAt(headerButton)
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
            val placeAction = ASK_MAPS_PLACE_ACTION
            classDefForEach { c ->
                if (owner == null && c.methods.any { it.literalAt(placeAction) >= 0 }) owner = c.type
            }
            // The action's Boolean answers: constants (FALSE), and this one, a boxed flag of the action's own.
            val show = mutableClassDefBy(owner ?: throw PatchException("place sheet Ask action not found")).methods.filter { m ->
                m.parameterTypes.isEmpty() && m.returnType == "Ljava/lang/Boolean;" &&
                    m.implementation?.instructions?.any { insn ->
                        insn.opcode == Opcode.IGET_BOOLEAN && ((insn as ReferenceInstruction).reference as FieldReference).definingClass == owner
                    } == true
            }.singleOrNull() ?: throw PatchException("place sheet Ask action has no visibility answer")
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

        // Issue #199: a place's AI description ("Peaceful nature park featuring ... -- Summarized with AI"), first on the
        // Overview tab and in the About tab. Both of its modules show the place's description and ask the place
        // whether AI wrote it; while hiding AI the place answers "no description" when it did, which is what a place
        // without one gets. The modules are the view models of the two layouts that show the disclaimer.
        run {
            val disclaimer = EDITORIAL_SUMMARY_DISCLAIMER
            val layouts = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
            classDefForEach { c ->
                if (!c.type.startsWith("Lorg/ungoogled/") && c.methods.any { it.literalAt(disclaimer) >= 0 }) layouts += c
            }
            val models = layouts.flatMap { layout ->
                val signature = layout.annotations.firstOrNull { it.type == "Ldalvik/annotation/Signature;" }?.elements
                    ?.firstOrNull { it.name == "value" }?.value as? com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
                val text = signature?.value?.joinToString("") { (it as com.android.tools.smali.dexlib2.iface.value.StringEncodedValue).value }.orEmpty()
                Regex("<(L[^<>;]+;)>").findAll(text).map { it.groupValues[1] }.toList()
            }.distinct().mapNotNull { classDefByOrNull(it) }
            if (models.size < 2) throw PatchException("AI description modules: found ${models.size}")
            fun MethodReference.key() = "$definingClass->$name()$returnType"
            fun calls(c: com.android.tools.smali.dexlib2.iface.ClassDef, returnType: String) = c.methods.flatMap { m ->
                m.implementation?.instructions?.mapNotNull { insn ->
                    ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                        insn.opcode == Opcode.INVOKE_VIRTUAL && it.parameterTypes.isEmpty() && it.returnType == returnType
                    }
                }.orEmpty()
            }.distinctBy { it.key() }
            // What both modules ask the place: whether AI wrote the description, and the description texts.
            val flags = models.map { m -> calls(m, "Z").map { it.key() }.toSet() }.reduce { a, b -> a intersect b }
            val flagKey = flags.singleOrNull() ?: throw PatchException("AI description flag: found ${flags.size}")
            val flag = calls(models.first(), "Z").first { it.key() == flagKey }
            val place = mutableClassDefBy(flag.definingClass)
            fun Method.fieldsRead() = implementation?.instructions?.mapNotNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { insn.opcode == Opcode.IGET_OBJECT }?.let { "${it.definingClass}->${it.name}" }
            }.orEmpty().toSet()
            val flagFields = place.methods.single { it.name == flag.name && it.parameterTypes.isEmpty() && it.returnType == "Z" }.fieldsRead()
            val texts = models.map { m -> calls(m, "Ljava/lang/String;").filter { it.definingClass == place.type }.map { it.key() }.toSet() }
                .reduce { a, b -> a intersect b }
            // The description the flag is about: the text getter reading the same part of the place as the flag.
            val described = place.methods.filter { m ->
                "${place.type}->${m.name}()${m.returnType}" in texts && m.parameterTypes.isEmpty() && (m.fieldsRead() intersect flagFields).isNotEmpty()
            }
            val text = described.singleOrNull() ?: throw PatchException("AI description text: found ${described.size}")
            // The getter has no register to spare (`this` is its only one), so its body moves to uaDescription()
            // and it becomes `return uaDescribed(this)`, which answers "" for an AI description while hiding AI.
            if (AccessFlags.STATIC.isSet(text.accessFlags)) throw PatchException("${place.type}->${text.name} is static")
            place.methods.add(
                ImmutableMethod(
                    place.type, "uaDescription", emptyList(), text.returnType,
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, MutableMethodImplementation(text.implementation!!),
                ).toMutable(),
            )
            place.methods.add(
                ImmutableMethod(
                    place.type, "uaDescribed", listOf(ImmutableMethodParameter(place.type, null, null)), text.returnType,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            sget-boolean v0, $SHAPES->HIDE_AI:Z
                            if-eqz v0, :described
                            invoke-virtual { p0 }, ${place.type}->${flag.name}()Z
                            move-result v0
                            if-eqz v0, :described
                            const-string v0, ""
                            return-object v0
                            :described
                            invoke-virtual { p0 }, ${place.type}->uaDescription()${text.returnType}
                            move-result-object v0
                            return-object v0
                        """,
                    )
                },
            )
            val body = text.implementation!!
            while (body.instructions.isNotEmpty()) body.removeInstruction(0)
            text.addInstructions(
                0,
                """
                    invoke-static { p0 }, ${place.type}->uaDescribed(${place.type})${text.returnType}
                    move-result-object p0
                    return-object p0
                """,
            )
        }

        // Issue #199: the place sheet's server-made actions. Next to its own ones (Directions, Call, ...) the server
        // can send action buttons of its own, each with a label and an icon picked from a list; Ask Maps comes as one
        // ("Ask", the magnifier with a spark) beside the "Ask" action above. Such a button is added to the action row
        // through `row.add(new ServerAction(..command..))`; while hiding AI that add skips a button whose icon is a
        // spark. The icon list is the one method mapping icon numbers to Maps' drawables, the sparks among them.
        run {
            val spark = SPARK_ICON
            val searchSpark = SEARCH_SPARK_ICON
            val mappers = mutableListOf<Pair<String, Method>>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                c.methods.filterTo(mutableListOf()) { m ->
                    m.parameterTypes.isNotEmpty() && m.literalAt(spark) >= 0 && m.literalAt(searchSpark) >= 0 &&
                        m.implementation?.instructions?.any { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH } == true
                }.forEach { mappers += c.type to it }
            }
            val (mapperOwner, mapperFound) = mappers.singleOrNull() ?: throw PatchException("server action icon list: found ${mappers.size}")
            val mapper = mutableClassDefBy(mapperOwner).methods.single {
                it.name == mapperFound.name && it.parameterTypes == mapperFound.parameterTypes && it.returnType == mapperFound.returnType
            }
            val icon = mapper.parameterTypes[0].toString()
            val code = mapper.implementation!!.instructions
            // `n = toNumber(icon.number); if (n == 0) n = 1; switch (n - 1)`
            val read = code.indexOfFirst { insn ->
                insn.opcode == Opcode.IGET && ((insn as ReferenceInstruction).reference as FieldReference).definingClass == icon
            }
            val number = (code.getOrNull(read) as? ReferenceInstruction)?.reference as? FieldReference
                ?: throw PatchException("server action icon list no longer reads the icon's number")
            val toNumber = (code.getOrNull(read + 1) as? ReferenceInstruction)?.reference as? MethodReference
            if (code[read + 1].opcode != Opcode.INVOKE_STATIC || toNumber?.parameterTypes?.map { it.toString() } != listOf("I") ||
                code.drop(read).take(8).none { it.opcode == Opcode.ADD_INT_LIT8 && (it as com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction).narrowLiteral == -1 }
            ) throw PatchException("server action icon list no longer turns the icon's number into a case")
            val payloads = code.filterIsInstance<com.android.tools.smali.dexlib2.builder.BuilderSwitchPayload>()
            val sparkCases = payloads.flatMap { it.switchElements }.filter { element ->
                val target = (element as com.android.tools.smali.dexlib2.builder.instruction.BuilderSwitchElement).target.location.index
                // each case loads its drawable first: `const v0, <drawable>; goto`
                (code[target] as? com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction)?.narrowLiteral in setOf(spark, searchSpark)
            }.map { it.key }
            if (sparkCases.size != 2) throw PatchException("server action icon list: ${sparkCases.size} spark cases")
            // The server action: the class whose constructor picks its icon this way, from its command's
            // `command.content.icon`, keeping the command in a field.
            val actions = mutableListOf<String>()
            classDefForEach { c ->
                if (c.methods.any { m ->
                        m.name == "<init>" && m.implementation?.instructions?.any { insn ->
                            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == mapperOwner && it.name == mapper.name } == true
                        } == true
                    }
                ) actions += c.type
            }
            // (Server-made header buttons pick their icon the same way but are kept in fields, never added to a row.)
            var hooked = 0
            for (actionType in actions) {
            val action = mutableClassDefBy(actionType)
            val init = action.methods.single { m ->
                m.name == "<init>" && m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == mapper.name } == true
            }
            val initCode = init.implementation!!.instructions.toList()
            fun fieldRead(type: String) = initCode.firstNotNullOfOrNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { insn.opcode == Opcode.IGET_OBJECT && it.type == type }
            }
            val iconField = fieldRead(icon) ?: throw PatchException("$actionType no longer reads its icon")
            val contentField = fieldRead(iconField.definingClass) ?: continue
            val commandField = initCode.firstNotNullOfOrNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                    insn.opcode == Opcode.IPUT_OBJECT && it.definingClass == action.type && it.type == contentField.definingClass
                }
            } ?: continue
            // Where it joins the row: `new ServerAction(..)`, then `row.add(it)`.
            val sites = mutableListOf<Triple<String, Method, Int>>()
            classDefForEach { c ->
                for (m in c.methods) {
                    val insns = m.implementation?.instructions?.toList() ?: continue
                    insns.forEachIndexed { i, insn ->
                        // its constructor call, whose first register is the new action (the new-instance may use another)
                        val ctor = (insn as? ReferenceInstruction)?.reference as? MethodReference
                        if ((insn.opcode != Opcode.INVOKE_DIRECT && insn.opcode != Opcode.INVOKE_DIRECT_RANGE) ||
                            ctor?.definingClass != action.type || ctor.name != "<init>"
                        ) return@forEachIndexed
                        val made = when (insn) {
                            is com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction -> insn.startRegister
                            else -> (insn as com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c).registerC
                        }
                        val add = (i + 1 until minOf(insns.size, i + 3)).firstOrNull { j ->
                            val r = (insns[j] as? ReferenceInstruction)?.reference as? MethodReference
                            insns[j].opcode == Opcode.INVOKE_VIRTUAL && r?.parameterTypes?.map { it.toString() } == listOf("Ljava/lang/Object;") && r.returnType == "V" &&
                                (insns[j] as com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c).let { it.registerCount == 2 && it.registerD == made }
                        } ?: return@forEachIndexed
                        sites += Triple(c.type, m, add)
                    }
                }
            }
            if (sites.isEmpty()) continue
            hooked += sites.size
            val keys = sparkCases.joinToString("\n") { "const/16 v1, $it\nif-eq v0, v1, :skip" }
            val added = mutableSetOf<String>()
            for ((owner, found, at) in sites) {
                val m = mutableClassDefBy(owner).methods.single { it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType }
                val call = m.implementation!!.instructions[at] as com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
                val list = (call as ReferenceInstruction).reference as MethodReference
                val helper = "uaAdd" + list.definingClass.removePrefix("L").removeSuffix(";").replace('/', '_')
                if (added.add(helper)) {
                    action.methods.add(
                        ImmutableMethod(
                            action.type, helper,
                            listOf(ImmutableMethodParameter(list.definingClass, null, null), ImmutableMethodParameter(action.type, null, null)), "V",
                            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(4),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    sget-boolean v0, $SHAPES->HIDE_AI:Z
                                    if-eqz v0, :add
                                    iget-object v0, p1, ${action.type}->${commandField.name}:${commandField.type}
                                    iget-object v0, v0, ${contentField.definingClass}->${contentField.name}:${contentField.type}
                                    if-eqz v0, :add
                                    iget-object v0, v0, ${iconField.definingClass}->${iconField.name}:${iconField.type}
                                    if-eqz v0, :add
                                    iget v0, v0, ${number.definingClass}->${number.name}:I
                                    invoke-static { v0 }, $toNumber
                                    move-result v0
                                    if-nez v0, :known
                                    const/4 v0, 0x1
                                    :known
                                    add-int/lit8 v0, v0, -0x1
                                    $keys
                                    :add
                                    invoke-virtual { p0, p1 }, $list
                                    :skip
                                    return-void
                                """,
                            )
                        },
                    )
                }
                m.replaceInstruction(at, "invoke-static { v${call.registerC}, v${call.registerD} }, ${action.type}->$helper(${list.definingClass}${action.type})V")
            }
            }
            if (hooked == 0) throw PatchException("no server action is added to a row")
        }

        // A saved list's "Ask" action: added after its own server check; skipped the way that check skips it.
        run {
            var field: FieldReference? = null
            val listAction = ASK_MAPS_LIST_ACTION
            classDefForEach { c ->
                if (field != null) return@classDefForEach
                val init = c.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
                val at = init.literalAt(listAction)
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
