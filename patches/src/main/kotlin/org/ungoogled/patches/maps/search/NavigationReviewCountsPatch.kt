package org.ungoogled.patches.maps.search

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel

private const val REVIEW_COUNTS = "Lorg/ungoogled/ui/ReviewCounts;"
private const val TO_RESULT = "Placemark has no location in conversion to NavigationSearchResult."
// string/SEARCH_ALONG_ROUTE_RESULT_OPEN: the "Open" a navigation search result's details line ends with.
private const val RESULT_OPEN = 0x7f141d1b

/**
 * Off navigation a search result reads "4.2 (1,600)"; navigation turns each place into a result of its own that keeps
 * the rating and drops the review count (issue #123). The count is kept, by the place's feature id, as the place is
 * turned into a result, and put after the rating where navigation shows one: the results' callouts on the map, the
 * card a tapped result opens, and the results list's details line.
 */
@Suppress("unused")
val navigationReviewCountsPatch = bytecodePatch(
    name = "Review counts in navigation search",
    description = "Places found while navigating show their review count next to the rating.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch)

    execute {
        fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference
        fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
        fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
        fun Instruction.typeRef() = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type
        fun Instruction.a() = (this as OneRegisterInstruction).registerA
        fun Method.ins() = implementation?.instructions?.toList().orEmpty()
        fun mutable(m: Method) = mutableClassDefBy(m.definingClass).methods.single {
            it.name == m.name && it.parameterTypes == m.parameterTypes && it.returnType == m.returnType
        }
        fun MethodReference.params() = parameterTypes.map(CharSequence::toString)
        // Whether an instruction in (from, to) may write the register.
        fun List<Instruction>.writes(reg: Int, from: Int, to: Int) = subList(from + 1, to).any { i ->
            i is OneRegisterInstruction && (i.opcode.setsRegister() || i.opcode.setsWideRegister()) &&
                (i.registerA == reg || (i.opcode.setsWideRegister() && i.registerA + 1 == reg))
        }

        val conversions = mutableListOf<Method>()
        val counted = mutableListOf<Method>()
        val details = mutableListOf<Method>()
        val formatted = mutableListOf<Method>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            c.methods.forEach { m ->
                val ins = m.ins()
                if (ins.isEmpty()) return@forEach
                if (ins.any { it.string() == TO_RESULT }) conversions += m
                if (ins.any { it.string() == "reviewCount" } && ins.any { it.string() == "ratingValue" }) counted += m
                if (m.returnType == "Ljava/lang/CharSequence;" && m.parameterTypes.isEmpty() &&
                    ins.any { (it as? WideLiteralInstruction)?.wideLiteral == RESULT_OPEN.toLong() }
                ) details += m
                if (ins.any { it.string() == "%.1f" } && ins.any { i ->
                        i.opcode == Opcode.INVOKE_STATIC && i.method()?.let { it.params() == listOf("F", "Z") && it.returnType == "Ljava/util/List;" } == true
                    }
                ) formatted += m
            }
        }

        // 1. Place -> navigation search result: the place type, and its feature id getter (the id is two longs).
        val conversion = conversions.singleOrNull() ?: throw PatchException("place to navigation result conversion: found ${conversions.size}")
        val convIns = conversion.ins()
        val place = convIns.firstOrNull { it.opcode == Opcode.CHECK_CAST }?.typeRef()
            ?: throw PatchException("the conversion no longer casts its place")
        fun isFeatureId(type: String) = type.startsWith("L") && classDefByOrNull(type)?.let { c ->
            val fields = c.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
            fields.size == 2 && fields.all { it.type == "J" } && "Ljava/lang/Comparable;" in c.interfaces
        } == true
        val placeIds = convIns.mapNotNull { it.method() }.filter {
            it.definingClass == place && it.parameterTypes.isEmpty() && isFeatureId(it.returnType)
        }.map { it.name to it.returnType }.distinct()
        val (placeId, featureId) = placeIds.singleOrNull() ?: throw PatchException("the place's feature id getter: found ${placeIds.size}")

        // 2. The place's review count: what app indexing reports as its AggregateRating's "reviewCount".
        val countIns = (counted.singleOrNull() ?: throw PatchException("place's AggregateRating: found ${counted.size}")).ins()
        val countAt = countIns.indexOfFirst { it.string() == "reviewCount" }
        val placeCount = countIns.subList(0, countAt).lastOrNull { i ->
            i.method()?.let { it.definingClass == place && it.parameterTypes.isEmpty() && it.returnType == "I" } == true
        }?.method()?.name ?: throw PatchException("the place's review count getter is no longer read for \"reviewCount\"")

        // 3. The results list's details line: its rating, formatted from the result's rating getter.
        val line = mutable(details.singleOrNull() ?: throw PatchException("navigation result details line: found ${details.size}"))
        val lineIns = line.ins()
        val ratedAt = lineIns.indexOfFirst { i ->
            i.opcode == Opcode.INVOKE_STATIC && i.method()?.let {
                it.returnType == "Ljava/lang/CharSequence;" && it.params() == listOf("Landroid/content/res/Resources;", "Ljava/lang/Float;")
            } == true
        }
        if (ratedAt < 2) throw PatchException("the details line no longer formats a rating")
        val getRating = lineIns[ratedAt - 2]
        val ratingRef = getRating.method()
        if (getRating.opcode != Opcode.INVOKE_INTERFACE || ratingRef?.returnType != "Ljava/lang/Float;" || ratingRef.parameterTypes.isNotEmpty() ||
            lineIns[ratedAt - 1].opcode != Opcode.MOVE_RESULT_OBJECT || lineIns[ratedAt - 1].a() != (lineIns[ratedAt] as Instruction35c).registerD
        ) throw PatchException("the details line's rating no longer comes straight from the result")
        val resultReg = (getRating as Instruction35c).registerC
        val resultType = ratingRef.definingClass
        val resultIds = classDefBy(resultType).methods.filter { it.parameterTypes.isEmpty() && it.returnType == featureId }
        val resultId = resultIds.singleOrNull()?.name ?: throw PatchException("navigation result's feature id getter: found ${resultIds.size}")
        val moved = lineIns.getOrNull(ratedAt + 1)
        if (moved?.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("the details line's rating is not kept")
        val ratingReg = moved.a()
        if (ratingReg == resultReg || ratingReg > 15 || resultReg > 15) throw PatchException("details line registers unusable")
        line.addInstructionsAtLabel(
            ratedAt + 2,
            """
                invoke-static { v$ratingReg, v$resultReg }, $REVIEW_COUNTS->rating(Ljava/lang/CharSequence;Ljava/lang/Object;)Ljava/lang/CharSequence;
                move-result-object v$ratingReg
            """,
        )

        // 4. The results' callouts on the map: a label of fragments (name; rating, stars; detour). After the stars,
        //    where every kind of callout meets again before its detour line, the count becomes one more fragment.
        fun isResult(type: String) = classDefByOrNull(type)?.let { resultType in it.interfaces || it.type == resultType } == true
        val callout = mutable(
            formatted.filter { it.returnType == "V" }.singleOrNull()
                ?: throw PatchException("navigation results' map callouts: found ${formatted.count { it.returnType == "V" }}"),
        )
        // The mutable method's instructions: their branches know their targets.
        val calloutIns = callout.ins()
        val starsAt = calloutIns.indexOfFirst { i ->
            i.opcode == Opcode.INVOKE_STATIC && i.method()?.let { it.params() == listOf("F", "Z") && it.returnType == "Ljava/util/List;" } == true
        }
        val ratingTextAt = calloutIns.indexOfFirst { it.string() == "%.1f" }
        val detour = calloutIns.firstNotNullOfOrNull { i ->
            i.method()?.takeIf { i.opcode == Opcode.INVOKE_STATIC && it.params() == listOf("Landroid/content/Context;", "Lj\$/time/Duration;", "Z") }?.returnType
        } ?: throw PatchException("callouts no longer format a detour")
        val merge = calloutIns.indexOfFirst { i ->
            i.opcode == Opcode.IGET_OBJECT && i.field()?.let { it.definingClass == detour && it.type == "Ljava/lang/String;" } == true
        }
        if (starsAt < 0 || ratingTextAt < 0 || merge < starsAt) throw PatchException("callouts: stars at $starsAt, detour line at $merge")
        val target = { i: Instruction -> (i as? BuilderOffsetInstruction)?.target?.location?.index }
        val styleTest = (0 until merge).firstOrNull { calloutIns[it].opcode == Opcode.IF_EQZ && target(calloutIns[it]) == merge }
            ?: throw PatchException("callouts no longer skip a missing rating style")
        val styleReg = calloutIns[styleTest].a()
        val factoryAt = (styleTest + 1 until merge).firstOrNull { i ->
            val ins = calloutIns[i]
            ins.opcode == Opcode.INVOKE_VIRTUAL && (ins as Instruction35c).registerCount == 2 && ins.registerD == styleReg &&
                ins.method()!!.params() == listOf("Ljava/lang/Object;")
        } ?: throw PatchException("callouts no longer make a fragment in the rating style")
        val factory = calloutIns[factoryAt].method()!!
        val fragmentsReg = (calloutIns[factoryAt] as Instruction35c).registerC
        val fragmentType = factory.returnType
        val addAt = (factoryAt + 1 until merge).firstOrNull { i ->
            calloutIns[i].opcode == Opcode.INVOKE_VIRTUAL && calloutIns[i].method()!!.let { it.params() == listOf(fragmentType) && it.returnType == "V" }
        } ?: throw PatchException("callouts no longer add their fragments")
        val add = calloutIns[addAt].method()!!
        val labelReg = (calloutIns[addAt] as Instruction35c).registerC
        val body = calloutIns.subList(factoryAt, addAt)
        val copy = body.firstOrNull { it.opcode == Opcode.INVOKE_VIRTUAL && it.method()!!.parameterTypes.isEmpty() && it.method()!!.returnType == "V" }?.method()?.name
            ?: throw PatchException("callout fragments are no longer copied on write")
        val instance = body.firstOrNull { it.opcode == Opcode.IGET_OBJECT }?.field()?.name
            ?: throw PatchException("callout fragments no longer expose their message")
        val textAt = body.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && it.field()?.type == "Ljava/lang/String;" }
        if (textAt < 0) throw PatchException("callout fragments no longer take a text")
        val text = body[textAt].field()!!
        val bits = body.subList(0, textAt).lastOrNull { it.opcode == Opcode.IPUT && it.field()?.definingClass == text.definingClass }?.field()?.name
            ?: throw PatchException("callout fragments no longer mark their text as set")
        // The rating style is read from the callout's styles in the rating case, the count's from the same styles
        // for the detour line that follows.
        val styleRead = calloutIns.subList(0, ratingTextAt).lastOrNull { it.opcode == Opcode.IGET_OBJECT && it.a() == styleReg }
            ?: throw PatchException("callouts no longer pick the rating style")
        val stylesReg = (styleRead as TwoRegisterInstruction).registerB
        val ratingStyle = styleRead.field()!!
        val countStyle = calloutIns.drop(merge).firstOrNull { i ->
            i.opcode == Opcode.IGET_OBJECT && (i as TwoRegisterInstruction).registerB == stylesReg && i.field()?.definingClass == ratingStyle.definingClass
        }?.field()?.name ?: throw PatchException("callouts no longer style their detour line")
        val calloutResult = (0 until merge).lastOrNull { i ->
            calloutIns[i].opcode == Opcode.CHECK_CAST && isResult(calloutIns[i].typeRef()!!)
        } ?: throw PatchException("callouts no longer cast their result")
        val calloutResultReg = calloutIns[calloutResult].a()
        val regs = listOf(labelReg, fragmentsReg, stylesReg, styleReg, calloutResultReg)
        if (regs.any { it > 15 } || regs.distinct().size != regs.size) throw PatchException("callout registers unusable: $regs")
        if (calloutIns.writes(labelReg, addAt, merge) || calloutIns.writes(fragmentsReg, factoryAt, merge) ||
            calloutIns.writes(stylesReg, ratingTextAt, merge) || calloutIns.writes(styleReg, styleTest, merge) ||
            calloutIns.writes(calloutResultReg, calloutResult, merge)
        ) throw PatchException("a callout register is reused before the detour line")
        callout.addInstructionsAtLabel(
            merge,
            "invoke-static { ${regs.joinToString { "v$it" }} }, $REVIEW_COUNTS->callout(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // 5. The card a tapped result opens: its rating line ("4.2" and stars), as its two cards hand it back.
        val cardLine = formatted.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Ljava/lang/CharSequence;" &&
                it.parameterTypes.size == 3 && it.parameterTypes[1].toString() == "Landroid/content/res/Resources;" && it.parameterTypes[2].toString() == "Z"
        }.singleOrNull() ?: throw PatchException("navigation result card's rating line: not found")
        var cards = 0
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            c.methods.forEach { m ->
                if (m.ins().none { it.method()?.let { r -> r.definingClass == cardLine.definingClass && r.name == cardLine.name && r.params() == cardLine.parameterTypes.map(CharSequence::toString) } == true }) return@forEach
                val mm = mutable(m)
                val ins = mm.ins()
                val at = ins.indexOfFirst { it.method()?.let { r -> r.definingClass == cardLine.definingClass && r.name == cardLine.name } == true }
                val call = ins[at] as Instruction35c
                val res = ins.getOrNull(at + 1)
                // Only the cards that hand the line straight back (every caller in 26.36.04 does).
                if (res?.opcode != Opcode.MOVE_RESULT_OBJECT || ins.getOrNull(at + 2)?.opcode != Opcode.RETURN_OBJECT) return@forEach
                val out = res.a()
                if (out == call.registerC || out > 15 || call.registerC > 15) return@forEach
                mm.addInstructionsAtLabel(
                    at + 2,
                    """
                        invoke-static { v$out, v${call.registerC} }, $REVIEW_COUNTS->card(Ljava/lang/CharSequence;Ljava/lang/Object;)Ljava/lang/CharSequence;
                        move-result-object v$out
                    """,
                )
                cards++
            }
        }
        if (cards == 0) throw PatchException("no card shows the navigation result's rating line")

        mutable(conversion).addInstructionsAtLabel(0, "invoke-static { p1 }, $REVIEW_COUNTS->keep(Ljava/lang/Object;)V")

        fun answer(name: String, value: String) {
            val getter = mutableClassDefBy(REVIEW_COUNTS).methods.singleOrNull {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
            } ?: throw PatchException("$REVIEW_COUNTS->$name() not found")
            val first = getter.implementation!!.instructions.first()
            if (first.opcode != Opcode.CONST_STRING) throw PatchException("$name() no longer starts with const-string")
            getter.replaceInstruction(0, "const-string v${first.a()}, \"$value\"")
        }
        answer("placeIdMethod", placeId)
        answer("placeCountMethod", placeCount)
        answer("resultIdMethod", resultId)
        answer("resultRatingMethod", ratingRef.name)
        answer("fragmentMethod", factory.name)
        answer("addMethod", add.name)
        answer("copyMethod", copy)
        answer("instanceField", instance)
        answer("textField", text.name)
        answer("bitsField", bits)
        answer("ratingStyleField", ratingStyle.name)
        answer("countStyleField", countStyle)
    }
}
