package org.ungoogled.patches.maps.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

private const val MEDIA_PANEL = "Lorg/ungoogled/ui/MediaPanel;"
// string/EXPAND_ and MINIMIZE_MEDIA_REMOTE_CONTROL_CONTENT_DESCRIPTION: the media button's two labels.
private const val EXPAND_MEDIA_CONTROLS = 0x7f140c75
private const val MINIMIZE_MEDIA_CONTROLS = 0x7f14136e

/**
 * Navigation's media panel covers Maps' own button column, the media button that closes it included (issue #136):
 * every time the button's state is set (it relabels itself Expand / Minimize media controls), the extension makes
 * sure the column follows the sheet up while the panel is open.
 */
@Suppress("unused")
val mediaPanelClosePatch = bytecodePatch(
    name = "Media panel can be closed",
    description = "Navigation's media button stays above the media controls, so they can be closed again.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch)

    execute {
        fun Method.hasLiteral(id: Int) = implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == id.toLong() } == true
        val buttons = mutableListOf<String>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            if (c.methods.any { it.hasLiteral(EXPAND_MEDIA_CONTROLS) } && c.methods.any { it.hasLiteral(MINIMIZE_MEDIA_CONTROLS) }) buttons += c.type
        }
        val button = mutableClassDefBy(buttons.singleOrNull() ?: throw PatchException("media button: found ${buttons.size}"))
        val relabel = button.methods.singleOrNull { it.name != "<init>" && it.hasLiteral(MINIMIZE_MEDIA_CONTROLS) }
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
    }
}
