package org.ungoogled.patches.maps.search.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel

/** The unified row oneof case of a sponsored placemark. */
private const val AD_CASE = 5

internal val hideAdsPatch = bytecodePatch(
    description = "Hides promoted map pins -- on the map, in search results and along a route, in directions and navigation -- " +
        "and \"Sponsored\" search result rows.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // Every edit below reads Shapes.HIDE_ADS, so the Customization screen's
    // "Hide sponsored content" switch can turn all of them off again; the
    // extension has to be merged in for that field to exist at all.
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("hideAdsPatched")

        /** Returns at once while hiding ads; v0 must be a local, not a parameter. */
        fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.leaveEarlyWhenHidingAds(leave: String) {
            val parameters = parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
            if (implementation!!.registerCount - parameters < 1) throw PatchException("$definingClass->$name has no local register")
            addInstructionsWithLabels(
                0,
                """
                    sget-boolean v0, $SHAPES->HIDE_ADS:Z
                    if-eqz v0, :ads_on
                    $leave
                """,
                ExternalLabel("ads_on", implementation!!.instructions.first()),
            )
        }

        // Promoted map pins: never request them. Their impression beacons (www.google.com/adview) are fired by
        // the native engine when a pin is drawn, so not requesting the pins also stops those. Every
        // ListPromotedPinAds request is built by one sender (two callers since 26.43): the one method that starts
        // a builder of the request proto, named in Maps' table of request protos by RPC.
        run {
            val request = RpcNamesFingerprint.matchAll().firstNotNullOfOrNull { match ->
                val code = match.method.implementation!!.instructions
                val at = match.instructionMatches.first().index
                // (the gRPC stub names its service the same way; only the table's key is a class)
                ((code.getOrNull(at - 1) as? ReferenceInstruction)?.reference as? StringReference)?.string
                    ?.takeIf { code[at - 1].opcode == Opcode.CONST_STRING && classDefByOrNull("L$it;") != null }
            }?.let { "L$it;" } ?: throw PatchException("ListPromotedPinAds request proto not found")
            val senders = mutableListOf<Pair<String, Method>>()
            classDefForEach { c ->
                if (c.type == request || c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                for (m in c.methods) {
                    val code = m.implementation?.instructions?.toList() ?: continue
                    val builds = code.indices.any { i ->
                        code[i].opcode == Opcode.SGET_OBJECT && ((code[i] as ReferenceInstruction).reference as FieldReference).let {
                            it.definingClass == request && it.type == request
                        } && ((code.getOrNull(i + 1) as? ReferenceInstruction)?.reference as? MethodReference)?.name == "createBuilder"
                    }
                    if (builds) senders += c.type to m
                }
            }
            val (owner, sender) = senders.singleOrNull() ?: throw PatchException("ListPromotedPinAds sender: found ${senders.size}")
            if (sender.returnType != "V") throw PatchException("ListPromotedPinAds sender returns ${sender.returnType}")
            mutableClassDefBy(owner).methods.single { it.name == sender.name && it.parameterTypes == sender.parameterTypes && it.returnType == "V" }
                .leaveEarlyWhenHidingAds("return-void")
        }

        // Promoted pins along a route (issue #64). Every directions request asks for them (its
        // promotedPinAdsRequestOptions), and the answer's promoted-pin list goes, apart from the map's own
        // request above, to two label drawers: the directions screen's and navigation's. Each takes that
        // list alone and hands it to the same label factory (which builds the promoted labels from it);
        // they return at once when hiding ads, as each already does for an empty list. The factory is
        // read off navigation's drawer, the one with a trace label; the directions one is the other
        // method of the same shape that calls it.
        run {
            val navigation = mutableClassDefBy(NavigationAdsLabelControllerFingerprint.method.definingClass)
            fun factoryCall(m: Method) = m.implementation?.instructions
                ?.filter { it.opcode == Opcode.INVOKE_STATIC }
                ?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                ?.firstOrNull { r ->
                    m.parameterTypes.size == 1 && r.parameterTypes.size == 5 &&
                        r.parameterTypes[0].toString() == m.parameterTypes[0].toString() &&
                        r.parameterTypes[1].toString() == "I" && r.parameterTypes[2].toString() == "Z"
                }
            val navDrawer = navigation.methods.singleOrNull { it.returnType == "V" && factoryCall(it) != null }
                ?: throw PatchException("navigation's promoted-pin drawer not found")
            val factory = factoryCall(navDrawer)!!
            val listType = navDrawer.parameterTypes[0].toString()
            val drawers = mutableListOf<Pair<String, String>>()
            classDefForEach { c ->
                for (m in c.methods) {
                    if (m.returnType != "V" || m.parameterTypes.map { it.toString() } != listOf(listType)) continue
                    val call = factoryCall(m) ?: continue
                    if (call.definingClass == factory.definingClass && call.name == factory.name) drawers += c.type to m.name
                }
            }
            if (drawers.size != 2) throw PatchException("expected the directions and navigation promoted-pin drawers, found $drawers")
            for ((owner, name) in drawers) {
                val drawer = mutableClassDefBy(owner).methods.single {
                    it.name == name && it.returnType == "V" && it.parameterTypes.map { t -> t.toString() } == listOf(listType)
                }
                if (drawer.implementation!!.registerCount - 2 < 1) throw PatchException("$owner.$name has no free register")
                drawer.leaveEarlyWhenHidingAds("return-void")
            }
        }

        // Promoted pins in search results (issue #71): the search list and the place sheet hand the search
        // response's ad pins, with their prepared labels, to one drawer -- the square pins carrying the ad's
        // tagline. It returns at once when hiding ads, as it already does for an empty list; the callout that
        // points at an off-screen ad pin answers "none", as it does for an empty list too. The ad proto is what
        // the ad POI is made from; the callout walks a list of them, the drawer takes that list.
        val adPoi = AdPoiFingerprint.classDef
        val adPoiFactory = adPoi.methods.singleOrNull {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == adPoi.type && it.parameterTypes.size == 1
        } ?: throw PatchException("the ad POI's factory not found")
        val adType = adPoiFactory.parameterTypes[0].toString()
        val callouts = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.parameterTypes.size != 1 || !m.returnType.startsWith("L") || m.parameterTypes[0].toString() == adType) continue
                val code = m.implementation?.instructions ?: continue
                val empty = code.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "isEmpty" }
                val walksAds = code.any { it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == adType }
                if (empty && walksAds && code.first().opcode == Opcode.IGET_OBJECT) callouts += c.type to m
            }
        }
        val (calloutOwner, calloutFound) = callouts.singleOrNull() ?: throw PatchException("ad pin callout: found ${callouts.size}")
        val adList = calloutFound.parameterTypes[0].toString()
        val callout = mutableClassDefBy(calloutOwner).methods.single {
            it.name == calloutFound.name && it.parameterTypes == calloutFound.parameterTypes && it.returnType == calloutFound.returnType
        }
        val none = callout.implementation!!.instructions.let { code ->
            val ret = code.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT }
            (ret - 1 downTo 0).firstNotNullOfOrNull { i -> ((code[i] as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { code[i].opcode == Opcode.SGET_OBJECT } }
        } ?: throw PatchException("ad pin callout no longer answers a constant for no ads")
        val drawers = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            for (m in c.methods) {
                val p = m.parameterTypes.map { it.toString() }
                if (m.returnType == "V" && p.size == 3 && p[0] == adList && p[1] == "I" && p[2].startsWith("L")) drawers += c.type to m
            }
        }
        val (drawerOwner, drawer) = drawers.singleOrNull() ?: throw PatchException("search ad pin drawer: found ${drawers.size}")
        mutableClassDefBy(drawerOwner).methods.single { it.name == drawer.name && it.parameterTypes == drawer.parameterTypes && it.returnType == "V" }
            .leaveEarlyWhenHidingAds("return-void")
        callout.leaveEarlyWhenHidingAds("sget-object v0, $none\nreturn-object v0")

        // Sponsored-pin placemark objects: the factory returns null instead of building one. Its callers already
        // skip a null result.
        mutableClassDefBy(adPoi.type).methods.single { it.name == adPoiFactory.name && it.parameterTypes == adPoiFactory.parameterTypes }
            .leaveEarlyWhenHidingAds("const/4 v0, 0x0\nreturn-object v0")

        // The search result: its sponsored placemarks, built from the response's unified rows (`row.case == 5`, the
        // ad oneof case), the gate that merges them into the results, the unified results list and the rows the
        // results screen shows ("Sponsored" cards).
        val result = mutableClassDefBy(SearchResultFingerprint.classDef.type)
        fun Method.refs() = implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference }.orEmpty()
        val adsList = result.methods.singleOrNull { m ->
            !AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "Ljava/util/List;" && m.parameterTypes.size == 2 &&
                m.refs().any { it is MethodReference && it.definingClass == "Lcom/google/common/collect/ImmutableList;" && it.name == "builder" }
        } ?: throw PatchException("search result's sponsored placemark list not found")
        val listCode = adsList.implementation!!.instructions.toList()
        val next = listCode.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> r.definingClass == "Ljava/util/Iterator;" && r.name == "next" } == true }
        val row = listCode.drop(next).firstOrNull { it.opcode == Opcode.CHECK_CAST }?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
            ?: throw PatchException("sponsored placemark list no longer walks the unified rows")
        val rowsField = (next downTo 0).firstNotNullOfOrNull { i ->
            ((listCode[i] as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { listCode[i].opcode == Opcode.IGET_OBJECT && it.definingClass == adsList.parameterTypes[0].toString() }
        } ?: throw PatchException("sponsored placemark list no longer reads the response's rows")
        // The oneof case: the row's int field compared with case numbers (the other int field, the presence bits, is masked).
        val caseField = listCode.indices.mapNotNull { i ->
            val f = (listCode[i] as? ReferenceInstruction)?.reference as? FieldReference
            if (listCode[i].opcode != Opcode.IGET || f?.definingClass != row || f.type != "I") null
            else f.takeIf { listCode.getOrNull(i + 1)?.opcode !in setOf(Opcode.AND_INT_LIT8, Opcode.AND_INT_LIT16, Opcode.AND_INT_2ADDR, Opcode.AND_INT) }
        }.map { it.name }.distinct().singleOrNull() ?: throw PatchException("unified row's oneof case not found")

        // The merge gate: `ads = sponsoredPlacemarks(); if (service == null || !service.params().flag) none else ...`.
        // The flag's value goes through HIDE_ADS (false stays false, true becomes !HIDE_ADS) on its way to the test.
        run {
            val sites = result.methods.flatMap { m ->
                val code = m.implementation?.instructions?.toList() ?: return@flatMap emptyList()
                code.indices.filter { i ->
                    code[i].opcode == Opcode.INVOKE_DIRECT && ((code[i] as ReferenceInstruction).reference as MethodReference).name == adsList.name &&
                        code.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT && code.getOrNull(i + 2)?.opcode == Opcode.IF_EQZ
                }.map { m to it }
            }
            val (gate, call) = sites.singleOrNull() ?: throw PatchException("sponsored placemark merge gate: found ${sites.size}")
            val code = gate.implementation!!.instructions
            val skip = (code[call + 2] as BuilderOffsetInstruction).target.location.index
            val flagTest = (call + 3 until minOf(code.size, call + 12)).firstOrNull { i ->
                code[i].opcode == Opcode.IF_EQZ && code[i - 1].opcode == Opcode.IGET_BOOLEAN &&
                    (code[i] as BuilderOffsetInstruction).target.location.index == skip
            } ?: throw PatchException("sponsored placemark merge gate no longer tests a flag")
            if (code[flagTest].location.labels.isNotEmpty()) throw PatchException("sponsored placemark flag test is a branch target")
            val flag = (code[flagTest] as OneRegisterInstruction).registerA
            gate.addInstructionsWithLabels(
                flagTest,
                """
                    if-eqz v$flag, :flag_off
                    sget-boolean v$flag, $SHAPES->HIDE_ADS:Z
                    xor-int/lit8 v$flag, v$flag, 0x1
                """,
                ExternalLabel("flag_off", code[flagTest]),
            )
        }
        adsList.leaveEarlyWhenHidingAds("sget-object v0, Ljava/util/Collections;->EMPTY_LIST:Ljava/util/List;\nreturn-object v0")

        // The unified results list: a place the rows turned into that is itself an ad (promoted hotels and the
        // like) is left out. The place type and its is-ad check are read off promoted-hotel logging.
        val (place, isAd) = PromotedHotelLogFingerprint.method.implementation!!.instructions.toList().let { code ->
            val nextAt = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> r.definingClass == "Ljava/util/Iterator;" && r.name == "next" } == true }
            val cast = code.drop(nextAt).firstOrNull { it.opcode == Opcode.CHECK_CAST }?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
            val check = code.drop(nextAt).firstNotNullOfOrNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                    insn.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == cast && it.returnType == "Z" && it.parameterTypes.isEmpty()
                }
            }
            (cast ?: throw PatchException("promoted hotel logging no longer walks places")) to
                (check ?: throw PatchException("promoted hotel logging no longer asks a place whether it is an ad")).name
        }
        val placeClass = mutableClassDefBy(place)
        if (placeClass.methods.none { it.name == "uaUnlessAd" }) {
            placeClass.methods.add(
                ImmutableMethod(
                    place, "uaUnlessAd", listOf(ImmutableMethodParameter(place, null, null)), place,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(2),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            sget-boolean v0, $SHAPES->HIDE_ADS:Z
                            if-eqz v0, :keep
                            invoke-virtual { p0 }, $place->$isAd()Z
                            move-result v0
                            if-eqz v0, :keep
                            const/4 p0, 0x0
                            :keep
                            return-object p0
                        """,
                    )
                },
            )
        }
        run {
            // In the unified list's loop: `place = builder.build(); all.add(place); ...`, back to the loop's hasNext.
            val sites = result.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) }.flatMap { m ->
                val code = m.implementation?.instructions?.toList() ?: return@flatMap emptyList()
                if (code.none { it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == row }) return@flatMap emptyList()
                code.indices.filter { i ->
                    code[i].opcode == Opcode.INVOKE_VIRTUAL && ((code[i] as ReferenceInstruction).reference as MethodReference).let {
                        it.returnType == place && it.parameterTypes.isEmpty()
                    } && code.getOrNull(i + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
                        ((code.getOrNull(i + 2) as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.name == "add" } == true
                }.map { m to it }
            }
            val (loop, build) = sites.singleOrNull() ?: throw PatchException("unified results list: found ${sites.size} places built")
            val code = loop.implementation!!.instructions
            val head = (build downTo 0).firstOrNull { i ->
                ((code[i] as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == "Ljava/util/Iterator;" && it.name == "hasNext" } == true
            } ?: throw PatchException("unified results list is no longer a loop")
            val built = (code[build + 1] as OneRegisterInstruction).registerA
            loop.addInstructionsWithLabels(
                build + 2,
                """
                    invoke-static/range { v$built .. v$built }, $place->uaUnlessAd($place)$place
                    move-result-object v$built
                    if-eqz v$built, :next_row
                """,
                ExternalLabel("next_row", code[head]),
            )
        }

        // The rows the results screen shows, sponsored cards among them: the getter that reads the rows kept from
        // the response hands them through a filter that drops the ad case while hiding ads.
        run {
            val keptFields = result.methods.flatMap { m ->
                val code = m.implementation?.instructions?.toList() ?: return@flatMap emptyList()
                code.indices.filter { i ->
                    code[i].opcode == Opcode.IGET_OBJECT && ((code[i] as ReferenceInstruction).reference as FieldReference).let {
                        it.definingClass == rowsField.definingClass && it.name == rowsField.name
                    }
                }.mapNotNull { i ->
                    (i + 1 until minOf(code.size, i + 25)).firstNotNullOfOrNull { j ->
                        ((code[j] as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                            code[j].opcode == Opcode.IPUT_OBJECT && it.definingClass == result.type && it.type == "Ljava/util/List;"
                        }
                    }
                }
            }.distinctBy { it.name }
            val kept = keptFields.singleOrNull() ?: throw PatchException("the search result's kept rows: found ${keptFields.size}")
            val getters = result.methods.filter { m ->
                m.parameterTypes.isEmpty() && m.returnType == "Ljava/util/List;" && m.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.IGET_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).name == kept.name &&
                        (insn.reference as FieldReference).definingClass == result.type
                } == true
            }
            val getter = getters.singleOrNull() ?: throw PatchException("the search result's rows getter: found ${getters.size}")
            result.methods.add(
                ImmutableMethod(
                    result.type, "uaDropAds", listOf(ImmutableMethodParameter("Ljava/util/List;", null, null)), "Ljava/util/List;",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(6),
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            sget-boolean v0, $SHAPES->HIDE_ADS:Z
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
                            instance-of v0, v3, $row
                            if-eqz v0, :add
                            move-object v4, v3
                            check-cast v4, $row
                            iget v0, v4, $row->$caseField:I
                            const/4 v4, 0x$AD_CASE
                            if-eq v0, v4, :loop
                            :add
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
            val returns = getter.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
            for (r in returns.reversed()) {
                val reg = (getter.implementation!!.instructions[r] as OneRegisterInstruction).registerA
                getter.addInstructionsAtLabel(
                    r,
                    """
                        invoke-static/range { v$reg .. v$reg }, ${result.type}->uaDropAds(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$reg
                    """,
                )
            }
        }
    }
}
