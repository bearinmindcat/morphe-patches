package org.ungoogled.patches.maps.placesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.literal
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderPackedSwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** ASKMAPS_ENTRYPOINT_LABEL, "Ask Maps": the search chip's label. */
private const val ASKMAPS_ENTRYPOINT_LABEL = 0x7f140320

/** ASK_MAPS_MODE_ASSISTIVE_SHORTCUT, "Ask Maps": the home screen shortcut chip's label. */
private const val ASK_MAPS_MODE_ASSISTIVE_SHORTCUT = 0x7f140363

/** The home screen's shortcut row (Ask Maps, Set home, Restaurants, ...) view model. */
private object HomeShortcutsRowFingerprint : Fingerprint(
    filters = listOf(string("HomeAssistiveShortcutsRowViewModelImpl")),
)

/** The home screen's "Ask Maps" shortcut chip, the only code that loads its label. */
private object HomeAskMapsShortcutFingerprint : Fingerprint(
    filters = listOf(literal(ASK_MAPS_MODE_ASSISTIVE_SHORTCUT)),
)

/** gs_search_spark_vd_theme_24: the magnifier-and-sparkle icon Maps draws only for Ask Maps. */
private const val SEARCH_SPARK_ICON = 0x7f0805f3

/** The place-page "Ask" action, the only code that names its "Ask Maps chip". */
private object AskMapsActionFingerprint : Fingerprint(
    filters = listOf(string("Ask Maps chip")),
)

/** The search chip's view model, the only code that loads its label. */
private object AskMapsChipFingerprint : Fingerprint(
    filters = listOf(literal(ASKMAPS_ENTRYPOINT_LABEL)),
)

internal val hideAskMapsPatch = bytecodePatch(
    description = "Hides \"Ask Maps\": the chip by the search bar and the Ask button on place " +
        "pages. Can be switched off on the Customization screen.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // HIDE_ASK_MAPS is refreshed from the Customization switch at every Activity attach.
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("hideAskMapsPatched")

        // The home screen's "Ask Maps" chip, first in the shortcut row under the search
        // bar. Signed in, the row's list getter adds it when the account is eligible:
        // `provider.get()` cast to the shortcut interface and added straight to the
        // row's list builder. It is the getter's only cast to that interface, so skip
        // the provider read and the add together; the instruction after the add
        // overwrites the register the switch is read into on both paths.
        val shortcutType = HomeAskMapsShortcutFingerprint.method.definingClass
        val shortcutInterfaces = mutableClassDefBy(shortcutType).interfaces
        val shortcutInterface = shortcutInterfaces.singleOrNull()
            ?: throw PatchException("Ask Maps shortcut $shortcutType has ${shortcutInterfaces.size} interfaces")
        val rowType = HomeShortcutsRowFingerprint.method.definingClass
        val rowList = mutableClassDefBy(rowType).methods.singleOrNull { m ->
            m.parameterTypes.isEmpty() && m.returnType == "Ljava/util/List;" &&
                m.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.CHECK_CAST &&
                        ((insn as ReferenceInstruction).reference as TypeReference).type == shortcutInterface
                } == true
        } ?: throw PatchException("home shortcut list getter not found in $rowType")
        val rowInsns = rowList.implementation!!.instructions.toList()
        val casts = rowInsns.indices.filter { i ->
            rowInsns[i].opcode == Opcode.CHECK_CAST &&
                ((rowInsns[i] as ReferenceInstruction).reference as TypeReference).type == shortcutInterface
        }
        val cast = casts.singleOrNull()
            ?: throw PatchException("expected one shortcut cast in $rowType list getter, found ${casts.size}")
        val shortcutReg = (rowInsns[cast] as OneRegisterInstruction).registerA
        val read = rowInsns.getOrNull(cast - 3)
        val add = rowInsns.getOrNull(cast + 1)
        val after = rowInsns.getOrNull(cast + 2)
        if (read == null || read.opcode != Opcode.IGET_OBJECT || (read as TwoRegisterInstruction).registerA != shortcutReg ||
            rowInsns[cast - 2].opcode != Opcode.INVOKE_INTERFACE ||
            rowInsns[cast - 1].opcode != Opcode.MOVE_RESULT_OBJECT ||
            add == null || add.opcode != Opcode.INVOKE_VIRTUAL ||
            after == null || after.opcode != Opcode.IGET_OBJECT || (after as TwoRegisterInstruction).registerA != shortcutReg
        ) throw PatchException("home Ask Maps shortcut add in $rowType has an unexpected shape")
        rowList.addInstructionsWithLabels(
            cast - 3,
            """
                sget-boolean v$shortcutReg, $SHAPES->HIDE_ASK_MAPS:Z
                if-nez v$shortcutReg, :skip_home_ask_maps
            """,
            ExternalLabel("skip_home_ask_maps", after),
        )

        // The place-page "Ask" button, signed in. The server sends it as a generic
        // server-driven action (label "Ask" and icon from the place data, its click runs
        // a server command), not as the Ask Maps action type further down. What marks
        // it is its icon: the action-icon mapper turns the icon enum into gs_search_spark
        // only for Ask Maps. The button list builder adds such actions straight away
        // (no visibility check), so skip that add when the action's icon is the
        // search-spark one, the same way the builder skips an action without data.
        var iconMapperType: String? = null
        var iconMapperName: String? = null
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in classDef.methods) {
                val insns = m.implementation?.instructions ?: continue
                if (m.parameterTypes.size != 3 || m.parameterTypes[1] != "Ljava/lang/String;") continue
                if (insns.none { it.opcode == Opcode.PACKED_SWITCH }) continue
                if (insns.none { it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == SEARCH_SPARK_ICON }) continue
                if (iconMapperType != null) throw PatchException("two action-icon mappers: $iconMapperType and ${classDef.type}")
                iconMapperType = classDef.type
                iconMapperName = m.name
            }
        }
        val mapper = mutableClassDefBy(iconMapperType ?: throw PatchException("action-icon mapper not found")).methods.single {
            it.name == iconMapperName && it.parameterTypes.size == 3 && it.parameterTypes[1] == "Ljava/lang/String;"
        }
        val mapperInsns = mapper.implementation!!.instructions.toList()
        val iconRead = mapperInsns.indexOfFirst { it.opcode == Opcode.IGET }
        if (iconRead < 0) throw PatchException("action-icon mapper reads no icon enum")
        val iconField = (mapperInsns[iconRead] as ReferenceInstruction).reference as FieldReference
        if (iconField.definingClass != mapper.parameterTypes[0].toString()) {
            throw PatchException("action-icon mapper reads ${iconField.definingClass}")
        }
        val norm = mapperInsns[iconRead + 1]
        if (norm.opcode != Opcode.INVOKE_STATIC ||
            mapperInsns[iconRead + 2].opcode != Opcode.MOVE_RESULT ||
            mapperInsns[iconRead + 3].opcode != Opcode.IF_NEZ ||
            mapperInsns[iconRead + 4].opcode != Opcode.CONST_4 ||
            (mapperInsns[iconRead + 4] as NarrowLiteralInstruction).narrowLiteral != 1 ||
            mapperInsns[iconRead + 5].opcode != Opcode.ADD_INT_LIT8 ||
            (mapperInsns[iconRead + 5] as NarrowLiteralInstruction).narrowLiteral != -1
        ) throw PatchException("action-icon mapper has an unexpected shape")
        val normRef = (norm as ReferenceInstruction).reference as MethodReference
        val switch = mapperInsns.single { it.opcode == Opcode.PACKED_SWITCH } as BuilderOffsetInstruction
        val payload = switch.target.location.instruction as? BuilderPackedSwitchPayload
            ?: throw PatchException("action-icon switch has no payload")
        val sparkKeys = payload.switchElements.filter { el ->
            val at = el.target.location.instruction
            at != null && at.opcode == Opcode.CONST && (at as NarrowLiteralInstruction).narrowLiteral == SEARCH_SPARK_ICON
        }.map { it.key }
        val sparkKey = sparkKeys.singleOrNull() ?: throw PatchException("search-spark icon has ${sparkKeys.size} switch keys")
        if (sparkKey !in 1..127) throw PatchException("search-spark key $sparkKey out of range")

        // The server-driven action: the one place-page action (same base class as the
        // Ask Maps action) whose constructor maps its icon.
        val actionBase = mutableClassDefBy(AskMapsActionFingerprint.method.definingClass).superclass
        val actionTypes = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/") || classDef.superclass != actionBase) return@classDefForEach
            if (classDef.methods.any { m ->
                    m.name == "<init>" && m.implementation?.instructions?.any { insn ->
                        insn.opcode == Opcode.INVOKE_VIRTUAL &&
                            ((insn as ReferenceInstruction).reference as MethodReference).let {
                                it.definingClass == mapper.definingClass && it.name == mapper.name &&
                                    it.parameterTypes.size == 3
                            }
                    } == true
                }) actionTypes += classDef.type
        }
        val xuiType = actionTypes.singleOrNull()
            ?: throw PatchException("expected one server-driven action, found ${actionTypes.size}: $actionTypes")
        val actionInit = mutableClassDefBy(xuiType).methods.single { m ->
            m.name == "<init>" && m.implementation?.instructions?.any {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((it as ReferenceInstruction).reference as MethodReference).definingClass == mapper.definingClass
            } == true
        }
        // The action's data (second constructor parameter) -> its look -> its icon.
        val actionData = actionInit.parameterTypes[1].toString()
        val initReads = actionInit.implementation!!.instructions.mapNotNull { insn ->
            if (insn.opcode != Opcode.IGET_OBJECT) null else (insn as ReferenceInstruction).reference as FieldReference
        }
        val lookField = initReads.firstOrNull { it.definingClass == actionData }
            ?: throw PatchException("server action's look field not found in $xuiType")
        val iconProtoField = initReads.firstOrNull { it.definingClass == lookField.type && it.type == iconField.definingClass }
            ?: throw PatchException("server action's icon field not found in $xuiType")

        // The button list builder: the one method that creates the server-driven action.
        val builders = mutableListOf<Pair<String, String>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/") || classDef.type == xuiType) return@classDefForEach
            for (m in classDef.methods) {
                if (m.implementation?.instructions?.any {
                        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == xuiType
                    } == true
                ) builders += classDef.type to m.name
            }
        }
        val (builderType, builderName) = builders.singleOrNull()
            ?: throw PatchException("expected one server-driven action builder, found ${builders.size}: $builders")
        val builder = mutableClassDefBy(builderType).methods.single { m ->
            m.name == builderName && m.implementation?.instructions?.any {
                it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == xuiType
            } == true
        }
        if (builder.parameterTypes.firstOrNull()?.toString() != actionData) {
            throw PatchException("action builder does not take the action data first")
        }
        val bInsns = builder.implementation!!.instructions.toList()
        val firstParam = builder.implementation!!.registerCount - builder.parameterTypes.size // p1 (no wide params before it)
        val creation = bInsns.indexOfFirst {
            it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == xuiType
        }
        // The register the builder copies its action data into and null-checks before
        // creating the server action.
        val copies = bInsns.filter {
            it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerB == firstParam
        }.map { (it as TwoRegisterInstruction).registerA }.toSet()
        val xuiGuard = bInsns.indices.firstOrNull { i ->
            i < creation && bInsns[i].opcode == Opcode.IF_EQZ && (bInsns[i] as OneRegisterInstruction).registerA in copies
        } ?: throw PatchException("action builder has no null check on its data before the server action")
        val dataReg = (bInsns[xuiGuard] as OneRegisterInstruction).registerA
        val noData = (bInsns[xuiGuard] as BuilderOffsetInstruction).target.location.instruction
            ?: throw PatchException("action builder's null check has no target")
        val next = bInsns[xuiGuard + 1]
        if (next !is TwoRegisterInstruction || next.opcode != Opcode.IGET_BOOLEAN || next.registerB != dataReg) {
            throw PatchException("action builder's data path does not start by reading the data")
        }
        val scratch = next.registerA
        if (scratch == dataReg || scratch > 15 || dataReg > 15) {
            throw PatchException("action builder registers unusable (v$scratch, v$dataReg)")
        }
        builder.addInstructionsWithLabels(
            xuiGuard + 1,
            """
                sget-boolean v$scratch, $SHAPES->HIDE_ASK_MAPS:Z
                if-eqz v$scratch, :keep_xui_action
                iget-object v$scratch, v$dataReg, $lookField
                if-eqz v$scratch, :keep_xui_action
                iget-object v$scratch, v$scratch, $iconProtoField
                if-eqz v$scratch, :keep_xui_action
                iget v$scratch, v$scratch, $iconField
                invoke-static {v$scratch}, $normRef
                move-result v$scratch
                if-nez v$scratch, :xui_icon_set
                const/4 v$scratch, 0x1
                :xui_icon_set
                add-int/lit8 v$scratch, v$scratch, -0x1
                add-int/lit8 v$scratch, v$scratch, -$sparkKey
                if-nez v$scratch, :keep_xui_action
                goto :skip_xui_action
                :keep_xui_action
                nop
            """,
            ExternalLabel("skip_xui_action", noData),
        )

        // The place-page button. Its c() is the visibility gate the header
        // actions dispatcher asks, fed by its field, which place data sets in
        // g(). c() itself has no scratch register, so keep the field off in
        // g(): v0 is dead after the store, which the method's own tail proves
        // by ending right there.
        val actionType = AskMapsActionFingerprint.method.definingClass
        val setter = mutableClassDefBy(actionType).methods.singleOrNull { m ->
            m.parameterTypes.size == 1 &&
                m.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.IPUT_BOOLEAN &&
                        ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.let { ref ->
                            ref.definingClass == actionType && ref.type == "Z"
                        } == true
                } == true
        } ?: throw PatchException("Ask Maps action setter not found in $actionType")
        val setImpl = setter.implementation!!
        if (setImpl.registerCount < 3) throw PatchException("Ask Maps action setter has no scratch register")
        val stores = setImpl.instructions.mapIndexedNotNull { i, insn ->
            if (insn.opcode == Opcode.IPUT_BOOLEAN &&
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.let { ref ->
                    ref.definingClass == actionType && ref.type == "Z"
                } == true
            ) i else null
        }
        val store = stores.singleOrNull()
            ?: throw PatchException("expected one Ask Maps action field store, found ${stores.size}")
        if (setImpl.instructions.size != store + 2 ||
            setImpl.instructions[store + 1].opcode != Opcode.RETURN_VOID
        ) throw PatchException("Ask Maps action setter does not end at its field store")
        val value = (setImpl.instructions[store] as TwoRegisterInstruction).registerA
        setter.addInstructionsWithLabels(
            store,
            """
                sget-boolean v0, $SHAPES->HIDE_ASK_MAPS:Z
                if-eqz v0, :keep_ask_maps
                const/4 v$value, 0x0
            """,
            ExternalLabel("keep_ask_maps", setImpl.instructions[store]),
        )

        // The search-bar chip. Its view model carries the "Ask Maps" label, a
        // sparkle icon and the click target; the one factory for it is the one
        // class that builds it. Starving the factory hides the chip on every
        // surface at once: every container either stores it in a field it
        // null-checks before showing, or adds it to a chip list that takes no
        // nulls, which the guards below skip instead.
        val chip = AskMapsChipFingerprint.method.definingClass
        val chipMethods = mutableClassDefBy(chip).methods
        if (chipMethods.count { it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Boolean;" } < 3) {
            throw PatchException("Ask Maps chip $chip has an unexpected shape")
        }
        val factories = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/") || classDef.type == chip) return@classDefForEach
            if (classDef.methods.any { m ->
                    m.implementation?.instructions?.any { insn ->
                        insn.opcode == Opcode.NEW_INSTANCE &&
                            ((insn as? ReferenceInstruction)?.reference as? TypeReference)?.type == chip
                    } == true
                }) factories += classDef.type
        }
        val factoryType = factories.singleOrNull()
            ?: throw PatchException("expected one Ask Maps chip factory, found ${factories.size}: $factories")
        val make = mutableClassDefBy(factoryType).methods.singleOrNull { m ->
            m.parameterTypes.size == 3 && m.returnType == chip
        } ?: throw PatchException("Ask Maps chip factory method not found in $factoryType")
        if (make.implementation!!.registerCount < 2) throw PatchException("Ask Maps chip factory has no local register")
        val makeFirst = make.implementation!!.instructions.first()
        if (makeFirst.opcode != Opcode.IGET ||
            (makeFirst as TwoRegisterInstruction).registerA != 0
        ) throw PatchException("Ask Maps chip factory does not start by reading its selector")
        make.addInstructionsWithLabels(
            0,
            """
                sget-boolean v0, $SHAPES->HIDE_ASK_MAPS:Z
                if-eqz v0, :show_ask_maps
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("show_ask_maps", makeFirst),
        )

        val sites = mutableListOf<Triple<String, String, Int>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                val insns = method.implementation?.instructions?.toList() ?: continue
                for (i in insns.indices) {
                    val insn = insns[i]
                    if (insn.opcode != Opcode.INVOKE_INTERFACE) continue
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (ref.name != "a" || ref.parameterTypes.size != 3 || ref.returnType != chip) continue
                    sites += Triple(
                        classDef.type,
                        "${method.name}${method.parameterTypes}${method.returnType}",
                        i,
                    )
                }
            }
        }
        if (sites.size != 8) throw PatchException("expected 8 Ask Maps chip builds, found ${sites.size}")
        var guard = 0
        for ((siteOwner, siteKey, siteGroup) in sites.groupBy { it.first to it.second }.map { (k, v) -> Triple(k.first, k.second, v) }) {
            val method = mutableClassDefBy(siteOwner).methods.singleOrNull {
                "${it.name}${it.parameterTypes}${it.returnType}" == siteKey
            } ?: throw PatchException("Ask Maps chip build site not found in $siteOwner")
            for ((_, _, i) in siteGroup.sortedByDescending { it.third }) {
                val insns = method.implementation!!.instructions
                val move = insns.getOrNull(i + 1)
                if (move == null || move.opcode != Opcode.MOVE_RESULT_OBJECT) {
                    throw PatchException("Ask Maps chip build in $siteOwner has an unexpected shape")
                }
                val chipReg = (move as OneRegisterInstruction).registerA
                val next = insns.getOrNull(i + 2)
                    ?: throw PatchException("Ask Maps chip build in $siteOwner ends the method")
                // A field store is fine empty: every reader skips it when null,
                // exactly as the app's own flag-off path does.
                if (next.opcode == Opcode.IPUT_OBJECT) continue
                if (next.opcode != Opcode.INVOKE_VIRTUAL && next.opcode != Opcode.INVOKE_VIRTUAL_RANGE &&
                    next.opcode != Opcode.INVOKE_INTERFACE && next.opcode != Opcode.INVOKE_INTERFACE_RANGE
                ) throw PatchException("Ask Maps chip build in $siteOwner feeds ${next.opcode}")
                val after = insns.getOrNull(i + 3)
                    ?: throw PatchException("Ask Maps chip list add in $siteOwner ends the method")
                method.addInstructionsWithLabels(
                    i + 2,
                    "if-eqz v$chipReg, :skip_ask_maps_$guard",
                    ExternalLabel("skip_ask_maps_$guard", after),
                )
                guard++
            }
        }
    }
}
