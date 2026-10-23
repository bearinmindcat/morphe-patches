package org.ungoogled.patches.maps.search.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** Sponsored-pin request builder: string-anchored, same style as the other single-string fingerprints. */
private object PromotedPinAdsRequestFingerprint : Fingerprint(
    name = "b",
    returnType = "V",
    parameters = listOf("Lcdsx;", "Lcjlj;", "Z"),
)

/**
 * Navigation's promoted-pin label controller: draws the promoted pins Maps gets with the route while
 * navigating. The class is the only one with this trace label.
 */
private object NavigationAdsLabelControllerFingerprint : Fingerprint(
    filters = listOf(string("NavigationAdsLabelController.onNavigationUiStateChanged")),
)

internal val hideAdsPatch = bytecodePatch(
    description = "Hides promoted map pins -- on the map and along a route, in directions and navigation -- " +
        "and \"Sponsored\" search result rows.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // Every edit below reads Shapes.HIDE_ADS, so the Customization screen's
    // "Hide sponsored content" switch can turn all of them off again; the
    // extension has to be merged in for that field to exist at all.
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("hideAdsPatched")

        /** At method entry v0 is a free local in all three (none takes more than 4 parameter registers). */
        fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.leaveEarlyWhenHidingAds(leave: String) {
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

        // Promoted map pins: never request them. Their impression beacons
        // (www.google.com/adview) are fired by the native engine when a pin is
        // drawn, so not requesting the pins also stops those.
        PromotedPinAdsRequestFingerprint.method.leaveEarlyWhenHidingAds("return-void")

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

        // Sponsored-pin placemark objects: the factory returns null instead of
        // building one. Both existing callers already skip a null result.
        AdPlacemarkFactoryFingerprint.method.leaveEarlyWhenHidingAds("const/4 v0, 0x0\nreturn-object v0")

        // Sponsored placemarks list, built from a search response: never build it.
        AdsListBuilderFingerprint.method.leaveEarlyWhenHidingAds(
            "sget-object v0, Ljava/util/Collections;->EMPTY_LIST:Ljava/util/List;\nreturn-object v0",
        )

        // Whether to ALSO merge the response's sponsored placemarks in: force
        // the "don't" path regardless of the caller's own opt-in flag, by
        // jumping to the same branch that flag already selects when off.
        SponsoredPlacemarksGateFingerprint.let { fp ->
            val ifEqzIndex = fp.instructionMatches.last().index
            // .instructionMatches can hand back an instruction still backed by
            // the original dex bytes rather than the mutable builder form a
            // branch target needs; re-fetch through the method itself, which
            // is guaranteed to be the live, mutable copy.
            val skipTo = (fp.method.getInstruction(ifEqzIndex) as BuilderInstruction21t).target
            val skipInstruction = skipTo.location.instruction
                ?: throw PatchException("aj()'s awcl-null branch target has no instruction")
            fp.method.addInstructionsWithLabels(
                ifEqzIndex,
                """
                    sget-boolean v12, $SHAPES->HIDE_ADS:Z
                    if-nez v12, :ua_skip
                """,
                ExternalLabel("ua_skip", skipInstruction),
            )
        }

        // Unified results list: two independent signals mark an entry as an ad
        // (the chaq oneof case, and a placemark's own is-ad query), both gated
        // here. Capture everything needed from the PRISTINE method first, then
        // apply the later-positioned edit before the earlier one, so neither
        // edit's captured indices are invalidated by the other shifting them.
        //
        // Each gate's "carry on" target is an ExternalLabel on the instruction
        // that follows it, never a label written at the end of the snippet:
        // addInstructionsWithLabels appends the external labels to the snippet
        // as `:name nop`, so a trailing internal label attaches to the first of
        // those nops and resolves to EXTERNAL label 0 -- here the loop head or
        // the skip target. An earlier version did that, and every normal row
        // was skipped: the results list came up empty (shown as a retry icon).
        val loopHead = UnifiedListLoopHeadFingerprint.instructionMatches
        // The live instruction from the method itself: an ExternalLabel needs the
        // mutable copy, which a fingerprint match does not always hand back.
        val loopHeadInstruction = UnifiedListLoopHeadFingerprint.method.implementation!!.instructions[loopHead.first().index]
        val gate1InsertAt = loopHead.last().index + 1
        val gate1Continue = UnifiedListLoopHeadFingerprint.method.implementation!!.instructions[gate1InsertAt]

        val fallback = FallbackPlacemarkFingerprint.instructionMatches
        val gate2InsertAt = fallback.last().index + 1
        val gate2Continue = FallbackPlacemarkFingerprint.method.implementation!!.instructions[gate2InsertAt]

        FallbackPlacemarkFingerprint.method.addInstructionsWithLabels(
            gate2InsertAt,
            """
                sget-boolean v5, $SHAPES->HIDE_ADS:Z
                if-eqz v5, :carry_on
                invoke-virtual {v2}, Loku;->cx()Z
                move-result v5
                if-nez v5, :ua_loop_head
            """,
            ExternalLabel("ua_loop_head", loopHeadInstruction),
            ExternalLabel("carry_on", gate2Continue),
        )
        UnifiedListLoopHeadFingerprint.method.addInstructionsWithLabels(
            gate1InsertAt,
            """
                sget-boolean v5, $SHAPES->HIDE_ADS:Z
                if-eqz v5, :carry_on
                iget v5, v2, Lchaq;->c:I
                add-int/lit8 v5, v5, -0x${AD_CASE.toString(16)}
                if-eqz v5, :ua_loop_head
            """,
            ExternalLabel("ua_loop_head", loopHeadInstruction),
            ExternalLabel("carry_on", gate1Continue),
        )

        // Sponsored search-results ROW: skip inserting it. The row's view
        // model is already handed off before this method's own null-check
        // reaches it, so the gate reuses that null-check's own branch target
        // -- what already happens for a row with nothing to show -- rather
        // than inventing a new one.
        val guardIfEqzIndex = SponsoredRowInsertGuardFingerprint.instructionMatches.first().index
        val skipTarget = (SponsoredRowInsertGuardFingerprint.method
            .getInstruction(guardIfEqzIndex) as BuilderInstruction21t).target
        val skipInstruction = skipTarget.location.instruction
            ?: throw PatchException("auwk.v()'s null-row branch target has no instruction")

        SponsoredRowHandoffFingerprint.let { fp ->
            val insertAt = fp.instructionMatches.last().index + 1
            val rowOn = fp.method.implementation!!.instructions[insertAt]
            fp.method.addInstructionsWithLabels(
                insertAt,
                """
                    sget-boolean v15, $SHAPES->HIDE_ADS:Z
                    if-eqz v15, :row_on
                    iget v15, v12, Lchaq;->c:I
                    add-int/lit8 v15, v15, -0x${AD_CASE.toString(16)}
                    if-nez v15, :row_on
                    goto :ua_skip_row
                """,
                ExternalLabel("ua_skip_row", skipInstruction),
                ExternalLabel("row_on", rowOn),
            )
        }
    }
}
