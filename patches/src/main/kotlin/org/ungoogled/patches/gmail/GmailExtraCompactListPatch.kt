package org.ungoogled.patches.gmail

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatch
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL
import org.ungoogled.patches.gmail.GmailConstants.GMAIL_EXTENSION
import org.w3c.dom.Element

/** The conversation list's rows: with sender pictures (Default and Comfortable density) and with checkboxes (Compact). */
private const val PICTURE_ROW = "res/layout/conversation_item_view.xml"
private const val COMPACT_ROW = "res/layout/conversation_item_view_compact.xml"
private const val DIMENS = "res/values/dimens.xml"
/** The gap above the subject and the snippet (3dp); Gmail also sets the snippet's from it in code, so the value changes. */
private const val LINE_GAP = "conv_list_item_details_padding_top"

/**
 * The spacing, tightened; text sizes stay. Picture rows (Default/Comfortable: 16dp above the sender, 16dp below the
 * snippet, a 40dp picture in a 72dp box, at least 88dp per row): 6dp above and below, a 36dp picture. Compact (8dp above
 * the sender, 8dp below, at least 56dp with a 48dp checkbox, and the star hanging below the subject line): 4dp above, 3dp
 * below. Lines 1dp apart instead of 3dp. Measured on the emulator (420 dpi): picture rows 96dp to 75dp apart, Compact
 * rows 60dp to 50dp.
 */
private val SPACING = mapOf(
    PICTURE_ROW to mapOf(
        // view id (null = the row itself) to its new attributes
        null to mapOf("android:paddingBottom" to "6.0dip", "android:minHeight" to "48.0dip"),
        // 16 + 36 + 12 wide, 6 + 36 + 6 tall: the picture's top is level with the sender's name.
        "contact_image" to mapOf(
            "android:layout_width" to "64.0dip", "android:layout_height" to "48.0dip",
            "android:paddingTop" to "6.0dip", "android:paddingBottom" to "6.0dip", "android:paddingEnd" to "12.0dip",
        ),
        "senders" to mapOf("android:layout_marginTop" to "6.0dip"),
        "attachment_chips" to mapOf("android:paddingTop" to "4.0dip"),
    ),
    COMPACT_ROW to mapOf(
        null to mapOf("android:paddingBottom" to "3.0dip", "android:minHeight" to "44.0dip"),
        "compact_checkbox" to mapOf("android:layout_height" to "40.0dip", "android:paddingTop" to "4.0dip"),
        "senders" to mapOf("android:layout_marginTop" to "4.0dip"),
        "subject" to mapOf("android:layout_marginTop" to "2.0dip"),
        "star" to mapOf("android:layout_height" to "20.0dip"),
    ),
)

private val gmailCompactRowPatch: ResourcePatch = resourcePatch {
    execute {
        decode(PICTURE_ROW, COMPACT_ROW, DIMENS)
        for ((file, views) in SPACING) {
            document(file).use { xml ->
                val root = xml.documentElement
                val all = xml.getElementsByTagName("*").let { list -> (0 until list.length).map { list.item(it) as Element } }
                fun view(id: String) = all.singleOrNull { it.getAttribute("android:id").substringAfter('/') == id }
                    ?: throw PatchException("$file: no view with id $id")
                for ((id, attributes) in views) {
                    val element = if (id == null) root else view(id)
                    for ((name, value) in attributes) {
                        if (!element.hasAttribute(name)) throw PatchException("$file: ${id ?: "the row"} has no $name")
                        element.setAttribute(name, value)
                    }
                }
                if (file == COMPACT_ROW) {
                    // The star sat under the date and reached below the subject line; now centred on the subject line.
                    val star = view("star")
                    val below = (0 until star.attributes.length).map { star.attributes.item(it).nodeName }
                        .singleOrNull { it.endsWith(":layout_constraintTop_toBottomOf") }
                        ?: throw PatchException("$file: the star is no longer placed under the date")
                    val app = below.substringBefore(':')
                    star.removeAttribute(below)
                    star.setAttribute("$app:layout_constraintTop_toTopOf", "@id/subject")
                    star.setAttribute("$app:layout_constraintBottom_toBottomOf", "@id/subject")
                }
            }
        }
        document(DIMENS).use { xml ->
            val dimens = xml.getElementsByTagName("dimen").let { list -> (0 until list.length).map { list.item(it) as Element } }
            val gap = dimens.singleOrNull { it.getAttribute("name") == LINE_GAP } ?: throw PatchException("$DIMENS: no $LINE_GAP")
            gap.textContent = "1.0dip"
        }
    }
}

/** Gmail's sender picture drawable in the conversation list (photos, letter tiles, their outline) draws circles. */
private const val CANVAS = "Landroid/graphics/Canvas;"
private const val DRAW_CIRCLE = "drawCircle"

private val gmailSquareAvatarsPatch: BytecodePatch = bytecodePatch {
    extendWith(GMAIL_EXTENSION)

    execute {
        if (gmailExtraCompactListPatch.options["squareAvatars"]?.value != true) return@execute
        // The abstract drawable every sender picture extends: a static (Canvas, Rect, Paint) helper draws the circle,
        // and the picture itself is painted through a BitmapShader.
        val candidates = mutableListOf<String>()
        classDefForEach { c ->
            if (c.superclass != "Landroid/graphics/drawable/Drawable;" || AccessFlags.ABSTRACT.isSet(c.accessFlags).not()) return@classDefForEach
            val helper = c.methods.any { m ->
                AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "V" &&
                    m.parameterTypes.map(CharSequence::toString) == listOf(CANVAS, "Landroid/graphics/Rect;", "Landroid/graphics/Paint;") &&
                    m.implementation?.instructions?.any { isDrawCircle(it) } == true
            }
            val shader = c.methods.any { m ->
                m.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.NEW_INSTANCE && ((insn as ReferenceInstruction).reference.toString() == "Landroid/graphics/BitmapShader;")
                } == true
            }
            if (helper && shader) candidates += c.type
        }
        val type = candidates.singleOrNull() ?: throw PatchException("sender picture drawable: expected one, found ${candidates.size}")
        var replaced = 0
        for (method in mutableClassDefBy(type).methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            for ((index, insn) in code.withIndex()) {
                if (!isDrawCircle(insn)) continue
                val target = "Lorg/ungoogled/gmail/Avatars;->drawCircle(${CANVAS}FFFLandroid/graphics/Paint;)V"
                val smali = if (insn is RegisterRangeInstruction) {
                    "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + 4} }, $target"
                } else {
                    val call = insn as Instruction35c
                    "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE}, v${call.registerF}, v${call.registerG} }, $target"
                }
                method.replaceInstruction(index, smali)
                replaced++
            }
        }
        // The picture or letter tile, and the outline around it.
        if (replaced != 2) throw PatchException("sender picture drawable: expected 2 circles, found $replaced")
    }
}

private fun isDrawCircle(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Boolean {
    if (insn.opcode != Opcode.INVOKE_VIRTUAL && insn.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val ref = (insn as ReferenceInstruction).reference as? MethodReference ?: return false
    return ref.definingClass == CANVAS && ref.name == DRAW_CIRCLE && ref.parameterTypes.map(CharSequence::toString) == listOf("F", "F", "F", "Landroid/graphics/Paint;")
}

@Suppress("unused")
val gmailExtraCompactListPatch: BytecodePatch = bytecodePatch(
    name = "Extra compact list",
    description = "Tightens the conversation list (all densities) so more mail fits on screen.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GMAIL)
    dependsOn(gmailCompactRowPatch, gmailSquareAvatarsPatch)

    booleanOption(
        key = "squareAvatars",
        default = false,
        title = "Square sender pictures",
        description = "Sender pictures in the conversation list as squares, as before 2014.",
    )

    execute {}
}
