package org.ungoogled.patches.photos

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Field
import org.ungoogled.patches.gmail.methodsUsing
import org.ungoogled.patches.gmail.mutable
import org.ungoogled.patches.gmail.single
import org.ungoogled.patches.gmail.stringOrNull

/** Every string the static initializer of [type] loads (an enum's constant names). */
internal fun BytecodePatchContext.clinitStrings(type: String): Set<String> =
    classDefBy(type).methods.firstOrNull { it.name == "<clinit>" }?.implementation?.instructions
        ?.mapNotNull { it.stringOrNull() }?.toSet().orEmpty()

/** A promo log line in the promo manager's render step (Photos' FeaturePromoManagerMixin). */
private const val SERVER_PROMO_WITHOUT_PROTO = "Server promo but no loaded server promo proto found in graph callback."
private const val FEATURE_PROMO_TO_STRING = "FeaturePromo{id="

/**
 * Photos' feature promos (grid banners, half sheets, tooltips, memory cards, the Create tab's "new" stamps) all
 * reach the screen through one method of the promo manager, which gets the promo it picked. [check] is the smali
 * that decides, with v0 = that promo; it leaves a boolean in v0 (true = do not show it).
 */
internal fun BytecodePatchContext.skipPromosWhen(what: String, check: (Promo) -> String) {
    val render = methodsUsing(setOf(SERVER_PROMO_WITHOUT_PROTO)).single(SERVER_PROMO_WITHOUT_PROTO, "promo render step")
    val method = render.method
    if (method.returnType != "V" || method.parameterTypes.size != 2 || AccessFlags.STATIC.isSet(method.accessFlags)) {
        throw PatchException("promo render step is no longer (chosen promo, proto)V")
    }
    val chosen = method.parameterTypes[0].toString()
    val promoType = methodsUsing(setOf(FEATURE_PROMO_TO_STRING)).single(FEATURE_PROMO_TO_STRING, "FeaturePromo").type
    val promoField = classDefBy(chosen).fields.filter { it.type == promoType && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .singleOrNull() ?: throw PatchException("the chosen promo no longer holds one FeaturePromo")
    val fields = classDefBy(promoType).fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
    fun enumField(vararg names: String, label: String) = fields.singleOrNull { f ->
        f.type.startsWith("L") && f.type != "Ljava/lang/String;" && clinitStrings(f.type).containsAll(names.toList())
    } ?: throw PatchException("FeaturePromo no longer has one $label field")
    val promo = Promo(
        type = promoType,
        id = fields.singleOrNull { it.type == "Ljava/lang/String;" }?.name ?: throw PatchException("FeaturePromo no longer has one id field"),
        category = enumField("MONETIZATION", "URGENT_PRIVACY_LEGAL", label = "category"),
        surface = enumField("ALL_PHOTOS_GRID", "ASK_PHOTOS", label = "surface"),
    )
    mutable(render).apply {
        val registers = implementation!!.registerCount
        // this, the chosen promo, the proto: three parameter registers, the chosen promo second.
        val chosenRegister = registers - 2
        if (registers - 3 < 2 || chosenRegister > 15) throw PatchException("promo render step has no room for the $what check")
        addInstructionsWithLabels(
            0,
            """
                iget-object v0, p1, $chosen->${promoField.name}:$promoType
                ${check(promo)}
                if-eqz v0, :show
                return-void
            """,
            ExternalLabel("show", implementation!!.instructions.first()),
        )
    }
}

/** Photos' FeaturePromo class and the fields the checks read. */
internal class Promo(val type: String, private val id: String, private val category: Field, private val surface: Field) {
    val idField get() = "$type->$id:Ljava/lang/String;"
    val categoryField get() = "$type->${category.name}:${category.type}"
    val surfaceField get() = "$type->${surface.name}:${surface.type}"
}
