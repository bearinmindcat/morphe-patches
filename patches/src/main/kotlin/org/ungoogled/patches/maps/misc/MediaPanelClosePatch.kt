package org.ungoogled.patches.maps.misc

import org.ungoogled.patches.maps.ui.mapsId
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

private const val MEDIA_PANEL = "Lorg/ungoogled/ui/MediaPanel;"
/** org.ungoogled.ui.ScrollRow: the media app row, scrolling sideways. */
private const val SCROLL_ROW = "Lorg/ungoogled/ui/ScrollRow;"
// string/EXPAND_ and MINIMIZE_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION: the media button's two labels.
private val EXPAND_MEDIA_CONTROLS get() = mapsId(ResourceType.STRING, "EXPAND_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION")
private val MINIMIZE_MEDIA_CONTROLS get() = mapsId(ResourceType.STRING, "MINIMIZE_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION")

/**
 * Navigation's media panel covers Maps' own button column, the media button that closes it included (issue #136):
 * every time the button's state is set (it relabels itself Expand / Minimize media controls), the extension makes
 * sure the column follows the sheet up while the panel is open.
 */
@Suppress("unused")
val mediaPanelClosePatch = bytecodePatch(
    name = "Media panel can be closed",
    description = "Navigation's media button stays above the media controls, so they can be closed again; the " +
        "lowered sheet ends right below them and the row of media apps scrolls.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch)

    execute {
        fun Method.hasLiteral(id: Int) = implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == id.toLong() } == true
        val buttons = mutableListOf<String>()
        val expand = EXPAND_MEDIA_CONTROLS
        val minimize = MINIMIZE_MEDIA_CONTROLS
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            if (c.methods.any { it.hasLiteral(expand) } && c.methods.any { it.hasLiteral(minimize) }) buttons += c.type
        }
        val button = mutableClassDefBy(buttons.singleOrNull() ?: throw PatchException("media button: found ${buttons.size}"))
        val relabel = button.methods.singleOrNull { it.name != "<init>" && it.hasLiteral(minimize) }
            ?: throw PatchException("media button's relabelling method not found")
        val context = button.fields.singleOrNull { it.type == "Landroid/content/Context;" && !AccessFlags.STATIC.isSet(it.accessFlags) }
            ?: throw PatchException("media button keeps no Context")
        // The first instruction writes a register it does not read: free for the call in front of it.
        val first = relabel.implementation!!.instructions.first()
        if (first.opcode != Opcode.IGET_BOOLEAN || (first as TwoRegisterInstruction).registerA == first.registerB) {
            throw PatchException("media button's relabelling no longer starts by reading its state")
        }
        val free = first.registerA
        if (free > 15) throw PatchException("media button's relabelling: v$free out of invoke range")
        relabel.addInstructions(
            0,
            """
                iget-object v$free, p0, ${button.type}->${context.name}:Landroid/content/Context;
                invoke-static { v$free }, $MEDIA_PANEL->changed(Ljava/lang/Object;)V
            """,
        )

        // Issue #78: the lowered sheet's height with the media controls shown is the ETA row's height plus the card's
        // measured height; the card keeps room under its Browse row (it is at least 124 dp tall), so the sheet showed
        // part of what follows it. The card's measured height goes through the extension, which ends it just below
        // the Browse row. The helper is the one method finding both the header and the card by id.
        val header = mapsId(ResourceType.ID, "header")
        val card = mapsId(ResourceType.ID, "media_app_remote_control")
        val helpers = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.returnType != "I" || m.parameterTypes.map { it.toString() } != listOf("Z")) continue
                if (m.hasLiteral(header) && m.hasLiteral(card)) helpers += c.type to m
            }
        }
        val (owner, found) = helpers.singleOrNull() ?: throw PatchException("lowered sheet height helper: found ${helpers.size}")
        val helper = mutableClassDefBy(owner).methods.single {
            it.name == found.name && it.returnType == "I" && it.parameterTypes == found.parameterTypes
        }
        val code = helper.implementation!!.instructions.toList()
        val cardAt = code.indexOfFirst { (it as? WideLiteralInstruction)?.wideLiteral == card.toLong() }
        // after the card's lookup: `height = card.getMeasuredHeight()`, then added to the header's
        val measured = (cardAt until code.size).firstOrNull { i ->
            ((code[i] as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.definingClass == "Landroid/view/View;" && it.name == "getMeasuredHeight"
            } == true
        } ?: throw PatchException("lowered sheet height helper no longer measures the card")
        val result = code.getOrNull(measured + 1)
        if (result?.opcode != Opcode.MOVE_RESULT) throw PatchException("lowered sheet height helper does not keep the card's height")
        val height = (result as OneRegisterInstruction).registerA
        if (code.getOrNull(measured + 2)?.opcode !in setOf(Opcode.ADD_INT_2ADDR, Opcode.ADD_INT)) {
            throw PatchException("lowered sheet height helper no longer adds the card's height")
        }
        if (helper.implementation!!.instructions[measured + 2].location.labels.isNotEmpty()) {
            throw PatchException("lowered sheet height helper's addition is a branch target")
        }
        if (height > 15) throw PatchException("lowered sheet height helper: v$height out of invoke range")
        helper.addInstructions(
            measured + 2,
            """
                invoke-static { v$height }, $MEDIA_PANEL->cardPeek(I)I
                move-result v$height
            """,
        )

        // Issue #206: the row of the other media apps under the card is a plain horizontal LinearLayout as wide as the
        // sheet, so the apps past the fifth or so were cut off. The panel's layout makes the card's layout (the one
        // naming the card's id) and then the row's; the row's view class becomes the extension's scrolling row.
        run {
            fun newInstances(m: Method) = m.implementation?.instructions?.mapNotNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? TypeReference)?.type?.takeIf { insn.opcode == Opcode.NEW_INSTANCE }
            }.orEmpty()
            val cardLayouts = mutableSetOf<String>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/") || c.superclass == "Ljava/lang/Object;") return@classDefForEach
                if (c.methods.any { it.parameterTypes.isEmpty() && it.returnType.startsWith("L") && it.hasLiteral(card) }) cardLayouts += c.type
            }
            val rows = mutableSetOf<String>()
            classDefForEach { c ->
                if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
                for (m in c.methods) {
                    val made = newInstances(m)
                    val at = made.indexOfFirst { it in cardLayouts }
                    if (at < 0) continue
                    val cardSuper = classDefByOrNull(made[at])?.superclass ?: continue
                    made.drop(at + 1).firstOrNull { classDefByOrNull(it)?.superclass == cardSuper }?.let { rows += it }
                }
            }
            val row = mutableClassDefBy(rows.singleOrNull() ?: throw PatchException("media app row layout: found ${rows.size}"))
            val views = row.methods.filter { m ->
                m.parameterTypes.isEmpty() && m.implementation?.instructions?.any {
                    it.opcode == Opcode.CONST_CLASS && ((it as ReferenceInstruction).reference as TypeReference).type == "Landroid/widget/LinearLayout;"
                } == true
            }
            val layout = views.singleOrNull() ?: throw PatchException("media app row's layout: found ${views.size}")
            val classes = layout.implementation!!.instructions.withIndex().filter { (_, insn) ->
                insn.opcode == Opcode.CONST_CLASS && ((insn as ReferenceInstruction).reference as TypeReference).type == "Landroid/widget/LinearLayout;"
            }
            val (at, insn) = classes.singleOrNull() ?: throw PatchException("media app row is no longer one LinearLayout")
            layout.replaceInstruction(at, "const-class v${(insn as OneRegisterInstruction).registerA}, $SCROLL_ROW")
        }
    }
}
