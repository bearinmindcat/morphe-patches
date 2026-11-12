package org.ungoogled.patches.maps.saved

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.shared.addInstructionsAtLabel

private const val BRIDGE = "Lorg/ungoogled/ui/NativePlaces;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val CONTEXT = "Landroid/content/Context;"
private const val PLACE = "Lorg/ungoogled/ui/SavedStore\$Place;"

internal fun BytecodePatchContext.backgroundPlaceDetails(
    placeType: String,
    name: MethodReference,
    feature: MethodReference,
    position: MethodReference,
    category: MethodReference,
    photos: MethodReference,
    photoField: String,
    rating: MethodReference,
    reviews: MethodReference,
) {
    // These are the existing 26.36 request/future adapters. Refuse a different data flow.
    if (placeType != "Loku;") throw PatchException("Native place details type changed")
    val fetcher = mutableClassDefBy("Larmy;")
    val request = fetcher.methods.singleOrNull {
        it.name == "a" && it.parameterTypes.map(CharSequence::toString) == listOf("Laril;", "Larhp;") &&
            it.returnType == "Lasal;"
    } ?: throw PatchException("Native place details service changed")
    val original = classDefByOrNull("Laqbk;")?.methods?.singleOrNull {
        it.name == "bG" && it.parameterTypes.map(CharSequence::toString) == listOf(placeType)
    } ?: throw PatchException("Native future adapter changed")
    val refs = original.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference }
    if (refs.none { it is StringReference && it.string == "No featureID or query available to fetch placemark" } ||
        refs.filterIsInstance<MethodReference>().none {
            it.definingClass == fetcher.type && it.name == request.name &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Laril;", "Larhp;")
        }
    ) throw PatchException("Native future adapter no longer uses the expected signed-out service")
    listOf(
        Triple("Lokx;", "m", listOf("Lbjap;")),
        Triple("Lokx;", "a", emptyList()),
        Triple("Lbjap;", "e", listOf(STRING)),
        Triple("Loku;", "l", emptyList()),
        Triple("Lazbk;", "k", emptyList()),
        Triple("Lazbk;", "b", listOf("Lj\$/time/DayOfWeek;")),
        Triple("Lazbb;", "d", listOf(CONTEXT)),
        Triple("Lazbb;", "l", listOf(CONTEXT)),
    ).forEach { (type, method, params) ->
        if (classDefByOrNull(type)?.methods?.none {
                it.name == method && it.parameterTypes.map(CharSequence::toString) == params
            } != false
        ) throw PatchException("Native details dependency changed: $type->$method")
    }
    fetcher.methods.filter { it.name == "<init>" }.forEach { constructor ->
        val returns = constructor.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
        returns.reversed().forEach {
            constructor.addInstructionsAtLabel(it.index, "invoke-static/range { p0 .. p0 }, $BRIDGE->bind($OBJECT)V")
        }
    }
    val graph = mutableClassDefBy("Lnhc;")
    if (graph.fields.none { it.name == "fn" && it.type == "Lcqsh;" }) {
        throw PatchException("Native place service provider changed")
    }
    graph.methods.filter { it.name == "<init>" }.forEach { constructor ->
        val returns = constructor.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
        returns.reversed().forEach {
            constructor.addInstructionsAtLabel(it.index, """
                move-object/from16 v0, p0
                iget-object v0, v0, Lnhc;->fn:Lcqsh;
                invoke-static { v0 }, $BRIDGE->bindProvider($OBJECT)V
            """)
        }
    }

    val extension = mutableClassDefBy(BRIDGE)
    fun replace(methodName: String, parameters: List<String>, result: String, registers: Int, code: String) {
        val nativeName = "uaSaved" + methodName.replaceFirstChar(Char::uppercaseChar)
        val nativeResult = if (result == PLACE) OBJECT else result
        fetcher.methods.add(
            ImmutableMethod(
                fetcher.type, nativeName, parameters.map { ImmutableMethodParameter(it, null, null) }, nativeResult,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(registers),
            ).toMutable().apply { addInstructions(0, code) },
        )
        extension.methods.remove(extension.methods.single { it.name == methodName })
        extension.methods.add(
            ImmutableMethod(
                BRIDGE, methodName, parameters.map { ImmutableMethodParameter(it, null, null) }, result,
                AccessFlags.STATIC.value, null, null, MutableMethodImplementation(parameters.size),
            ).toMutable().apply {
                addInstructions(0, """
                    invoke-static/range { p0 .. p${parameters.size - 1} }, ${fetcher.type}->$nativeName(${parameters.joinToString("")})$nativeResult
                    move-result-object p0
                    ${if (result == PLACE) "check-cast p0, $PLACE" else ""}
                    return-object p0
                """)
            },
        )
    }
    replace("resolve", listOf(OBJECT), OBJECT, 1, """
        check-cast p0, Lcqsh;
        invoke-interface { p0 }, Lcqsh;->a()$OBJECT
        move-result-object p0
        return-object p0
    """)
    replace("request", listOf(OBJECT, STRING), OBJECT, 10, """
        new-instance v0, Lcom/google/common/util/concurrent/SettableFuture;
        invoke-direct { v0 }, Lcom/google/common/util/concurrent/SettableFuture;-><init>()V
        new-instance v1, Lokx;
        invoke-direct { v1 }, Lokx;-><init>()V
        invoke-static { p1 }, Lbjap;->e($STRING)Lbjap;
        move-result-object v2
        invoke-virtual { v1, v2 }, Lokx;->m(Lbjap;)V
        invoke-virtual { v1 }, Lokx;->a()Loku;
        move-result-object v2
        new-instance v3, Lawvj;
        const/4 v4, 0x0
        const/4 v5, 0x1
        invoke-direct { v3, v4, v2, v5, v5 }, Lawvj;-><init>(Lawvg;Ljava/io/Serializable;ZZ)V
        invoke-static { }, Larhp;->a()Larho;
        move-result-object v1
        invoke-virtual { v1, v3 }, Larho;->g(Lawvj;)V
        invoke-virtual { v1 }, Larho;->a()Larhp;
        move-result-object v2
        new-instance v3, Laqbf;
        invoke-direct { v3, v0 }, Laqbf;-><init>(Lcom/google/common/util/concurrent/SettableFuture;)V
        check-cast p0, Larmy;
        invoke-virtual { p0, v3, v2 }, Larmy;->a(Laril;Larhp;)Lasal;
        return-object v0
    """)
    replace("hours", listOf(OBJECT, CONTEXT), STRING, 10, """
        check-cast p0, Loku;
        invoke-virtual { p0 }, Loku;->l()Lazbk;
        move-result-object v0
        invoke-virtual { v0 }, Lazbk;->k()Z
        move-result v1
        if-nez v1, :known
        const-string v0, ""
        return-object v0
        :known
        new-instance v1, Ljava/lang/StringBuilder;
        invoke-direct { v1 }, Ljava/lang/StringBuilder;-><init>()V
        invoke-static { }, Lj${'$'}/time/DayOfWeek;->values()[Lj${'$'}/time/DayOfWeek;
        move-result-object v2
        const/4 v3, 0x0
        array-length v4, v2
        :day
        if-ge v3, v4, :done
        aget-object v5, v2, v3
        invoke-virtual { v0, v5 }, Lazbk;->b(Lj${'$'}/time/DayOfWeek;)Lazbb;
        move-result-object v5
        invoke-virtual { v5, p1 }, Lazbb;->d($CONTEXT)$STRING
        move-result-object v6
        invoke-virtual { v1, v6 }, Ljava/lang/StringBuilder;->append($STRING)Ljava/lang/StringBuilder;
        const-string v6, ": "
        invoke-virtual { v1, v6 }, Ljava/lang/StringBuilder;->append($STRING)Ljava/lang/StringBuilder;
        invoke-virtual { v5, p1 }, Lazbb;->l($CONTEXT)$STRING
        move-result-object v6
        invoke-virtual { v1, v6 }, Ljava/lang/StringBuilder;->append($STRING)Ljava/lang/StringBuilder;
        const-string v6, "\n"
        invoke-virtual { v1, v6 }, Ljava/lang/StringBuilder;->append($STRING)Ljava/lang/StringBuilder;
        add-int/lit8 v3, v3, 0x1
        goto :day
        :done
        invoke-virtual { v1 }, Ljava/lang/StringBuilder;->toString()$STRING
        move-result-object v0
        return-object v0
    """)
    replace("details", listOf(OBJECT, CONTEXT), PLACE, 12, """
        invoke-static { p0, p1 }, Larmy;->uaSavedHours($OBJECT$CONTEXT)$STRING
        move-result-object v8
        check-cast p0, $placeType
        invoke-virtual { p0 }, $name
        move-result-object v0
        invoke-virtual { p0 }, $feature
        move-result-object v1
        invoke-virtual { p0 }, $position
        move-result-object v2
        invoke-virtual { p0 }, $category
        move-result-object v3
        invoke-virtual { p0 }, $photos
        move-result-object v4
        const-string v5, "$photoField"
        invoke-virtual { p0 }, $rating
        move-result v6
        invoke-virtual { p0 }, $reviews
        move-result v7
        invoke-static/range { v0 .. v8 }, $BRIDGE->capture($STRING$OBJECT$OBJECT$STRING$OBJECT${STRING}FI$STRING)$OBJECT
        move-result-object v0
        return-object v0
    """)
}
