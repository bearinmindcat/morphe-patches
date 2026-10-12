package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.ungoogled.patches.maps.ui.customization.customizationScreenPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch

/** The account sheet row's listener: opens Waze & external data source with the sheet left open behind it. */
private const val OPEN_WAZE = "Lorg/ungoogled/ui/CustomizationActivity\$OpenWaze;"
/** drawable/gs_directions_car_vd_theme_24 in this build: a car, tinted like the sheet's other icons. */
private const val CAR_ICON = 0x7f080548

/**
 * A "Waze & external data source" row on the account sheet -- the speeding chime and speed cameras -- under
 * Power Saving Options and above Local saved. It opens a page of the Customization screen.
 * Shared, so either patch brings it.
 */
internal val wazeFeaturesRowPatch = bytecodePatch(
    description = "Adds the Waze & external data source row to the account sheet.",
) {
    dependsOn(sharedExtensionPatch, customizationScreenPatch)

    // In finalize, after every patch has run, so the sheet's other rows of ours are in place.
    finalize {
        val rowHolder = mutableClassDefBy("Lolr;")
        val template = rowHolder.methods.single { it.name == "a" && it.returnType == "Lbrmi;" && it.parameterTypes.isEmpty() }
        val templateRefs = template.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        listOf(
            "Lolr;->a:Lnxb;", "Lmm;->s(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;",
            "Lbrmi;->a()Lbrmg;", "Lbrmg;->c(I)V", "Lbrmg;->d(Ljava/lang/String;)V", "Lbrmg;->f(I)V",
            "Lbrmg;->e(Landroid/view/View\$OnClickListener;)V", "Lbrmf;->d:Lbrmf;", "Lbrmg;->a()Lbrmi;",
        ).forEach { if (it !in templateRefs) throw PatchException("account sheet row builder no longer uses $it") }
        // Built like the Customization and Power Saving Options rows.
        rowHolder.methods.add(
            ImmutableMethod(
                rowHolder.type, "uaWazeRow", emptyList(), "Lbrmi;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, MutableMethodImplementation(6),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        iget-object v0, p0, Lolr;->a:Lnxb;
                        const v1, $CAR_ICON
                        invoke-static { v0, v1 }, Lmm;->s(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;
                        move-result-object v1
                        invoke-static { }, Lbrmi;->a()Lbrmg;
                        move-result-object v3
                        invoke-static { }, Landroid/view/View;->generateViewId()I
                        move-result v4
                        invoke-virtual { v3, v4 }, Lbrmg;->c(I)V
                        iput-object v1, v3, Lbrmg;->a:Landroid/graphics/drawable/Drawable;
                        const-string v4, "Waze & external data source"
                        invoke-virtual { v3, v4 }, Lbrmg;->d(Ljava/lang/String;)V
                        const v4, 0x161a8
                        invoke-virtual { v3, v4 }, Lbrmg;->f(I)V
                        new-instance v2, $OPEN_WAZE
                        invoke-direct { v2, v0 }, $OPEN_WAZE-><init>(Landroid/content/Context;)V
                        invoke-virtual { v3, v2 }, Lbrmg;->e(Landroid/view/View${'$'}OnClickListener;)V
                        sget-object v4, Lbrmf;->d:Lbrmf;
                        iput-object v4, v3, Lbrmg;->d:Lbrmf;
                        invoke-virtual { v3 }, Lbrmg;->a()Lbrmi;
                        move-result-object v0
                        return-object v0
                    """,
                )
            },
        )

        fun calls(insn: Instruction, name: String) =
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                r.definingClass == "Lolr;" && r.name == name
            } == true

        // Both sheet builders: after Power Saving Options' row when it is there, else right after
        // Customization's. Power Saving Options always goes right after Customization, so whichever
        // finalize runs first the order is Customization, Power Saving Options, Waze & external data source, then
        // Local saved (added during execute, right after Customization).
        fun addUnderPowerSaving(returnType: String, parameters: List<String>) {
            val found = mutableListOf<Pair<String, String>>()
            classDefForEach { c ->
                for (m in c.methods) {
                    if (m.returnType != returnType || m.parameterTypes.map { it.toString() } != parameters) continue
                    if (m.implementation?.instructions?.any { calls(it, "uaCustomizationRow") } == true) found += c.type to m.name
                }
            }
            val (owner, name) = found.singleOrNull()
                ?: throw PatchException("account sheet builder $returnType(${parameters.joinToString()}) with the Customization row: found ${found.size}")
            val method = mutableClassDefBy(owner).methods.single {
                it.name == name && it.returnType == returnType && it.parameterTypes.map { t -> t.toString() } == parameters
            }
            val ins = method.implementation!!.instructions
            val call = ins.indexOfFirst { calls(it, "uaCustomizationRow") }
            val result = ins[call + 1]
            val add = ins[call + 2]
            if (result.opcode != Opcode.MOVE_RESULT_OBJECT || add.opcode != Opcode.INVOKE_VIRTUAL ||
                ((add as ReferenceInstruction).reference as MethodReference).let { it.definingClass != "Lbwxy;" || it.name != "i" }
            ) throw PatchException("Customization row add changed shape")
            // The row's result register is dead after its add, so it carries ours.
            val scratch = (result as OneRegisterInstruction).registerA
            val list = (add as Instruction35c).registerC
            val holder = (ins[call] as Instruction35c).registerC
            // Modern builder: the holder is cast from a wider register just before; do the same.
            val cast = ins.getOrNull(call - 1)?.takeIf {
                it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == "Lolr;"
            }
            val source = cast?.let { (ins[call - 2] as TwoRegisterInstruction).registerB }
            val power = (call + 3 until minOf(ins.size, call + 10)).firstOrNull { calls(ins[it], "uaPowerRow") }
            val at = if (power == null) call + 3 else {
                val powerAdd = (power + 1 until minOf(ins.size, power + 4)).firstOrNull {
                    ins[it].opcode == Opcode.INVOKE_VIRTUAL &&
                        ((ins[it] as ReferenceInstruction).reference as MethodReference).let { r -> r.definingClass == "Lbwxy;" && r.name == "i" }
                } ?: throw PatchException("Power Saving Options row add not found")
                powerAdd + 1
            }
            method.addInstructions(
                at,
                if (source != null) """
                    move-object v$scratch, v$source
                    check-cast v$scratch, Lolr;
                    invoke-virtual { v$scratch }, Lolr;->uaWazeRow()Lbrmi;
                    move-result-object v$scratch
                    invoke-virtual { v$list, v$scratch }, Lbwxy;->i(Ljava/lang/Object;)V
                """ else """
                    invoke-virtual { v$holder }, Lolr;->uaWazeRow()Lbrmi;
                    move-result-object v$scratch
                    invoke-virtual { v$list, v$scratch }, Lbwxy;->i(Ljava/lang/Object;)V
                """,
            )
        }
        addUnderPowerSaving("Lbrhh;", listOf("Lafmm;"))
        addUnderPowerSaving("Lbrfb;", listOf("Z"))
    }
}
