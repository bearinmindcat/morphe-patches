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

/** The conversation list's row at Settings > General settings > Conversation list density > Compact. */
private const val COMPACT_ROW = "res/layout/conversation_item_view_compact.xml"

/**
 * Compact's spacing, tightened (Compact: 8dp above the sender, 3dp between the lines, 8dp below, at least 56dp per
 * row with a 48dp checkbox, and the star hanging below the subject line). Text sizes stay. Measured on the emulator
 * (420 dpi): rows from 60dp to 50dp apart, 14 instead of 12 on a phone screen.
 */
private val SPACING = mapOf(
    // view id (null = the row itself) to its new attributes
    null to mapOf("android:paddingBottom" to "3.0dip", "android:minHeight" to "44.0dip"),
    "compact_checkbox" to mapOf("android:layout_height" to "40.0dip", "android:paddingTop" to "4.0dip"),
    "senders" to mapOf("android:layout_marginTop" to "4.0dip"),
    "subject" to mapOf("android:layout_marginTop" to "2.0dip"),
    "star" to mapOf("android:layout_height" to "20.0dip"),
)

private val gmailCompactRowPatch: ResourcePatch = resourcePatch {
    execute {
        decode(COMPACT_ROW)
        document(COMPACT_ROW).use { xml ->
            val root = xml.documentElement
            val all = xml.getElementsByTagName("*").let { list -> (0 until list.length).map { list.item(it) as Element } }
            for ((id, attributes) in SPACING) {
                val element = if (id == null) root else all.singleOrNull { it.getAttribute("android:id").substringAfter('/') == id }
                    ?: throw PatchException("$COMPACT_ROW: no view with id $id")
                for ((name, value) in attributes) {
                    if (!element.hasAttribute(name)) throw PatchException("$COMPACT_ROW: ${id ?: "the row"} has no $name")
                    element.setAttribute(name, value)
                }
            }
            // The star sat under the date and reached below the subject line; it is now centred on the subject line.
            val star = all.single { it.getAttribute("android:id").substringAfter('/') == "star" }
            val below = (0 until star.attributes.length).map { star.attributes.item(it).nodeName }
                .singleOrNull { it.endsWith(":layout_constraintTop_toBottomOf") }
                ?: throw PatchException("$COMPACT_ROW: the star is no longer placed under the date")
            val app = below.substringBefore(':')
            star.removeAttribute(below)
            star.setAttribute("$app:layout_constraintTop_toTopOf", "@id/subject")
            star.setAttribute("$app:layout_constraintBottom_toBottomOf", "@id/subject")
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
    description = "Tightens the Compact conversation list so more mail fits on screen.",
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
