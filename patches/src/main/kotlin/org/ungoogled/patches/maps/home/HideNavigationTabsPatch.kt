package org.ungoogled.patches.maps.home

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

internal val hideNavigationTabsPatch = bytecodePatch(
    description = "Hides the Explore / Contribute / You strip at the bottom of the home screen. Can be " +
        "switched back on on the Customization screen.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("hideTabsPatched")

        // The home fragment binds the bottom tab strip once: `this.strip = factory.create(container)`, the one
        // (ViewGroup) -> view holder call it keeps in a field. Right after the store the strip's root view is
        // hidden while the switch is on; the map's bottom inset follows the strip's laid-out height, so nothing
        // is left reserved for it. The work is a static method added to the holder, so the bind needs no
        // register of its own.
        val home = HomeFragmentIdFingerprint.classDef.type
        val bind = Fingerprint(
            filters = listOf(
                methodCall(opcode = Opcode.INVOKE_INTERFACE, parameters = listOf("Landroid/view/ViewGroup;")),
                opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately()),
                fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", location = MatchAfterImmediately()),
            ),
            custom = { _, c -> c.type == home },
        )
        val matches = bind.matchAll()
        val match = matches.singleOrNull() ?: throw PatchException("home tab strip bind: found ${matches.size}")
        val code = match.method.implementation!!.instructions
        val call = code[match.instructionMatches.first().index]
        val holder = ((call as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction).reference as MethodReference).returnType
        val holderClass = mutableClassDefBy(holder)
        val viewGetter = holderClass.methods.singleOrNull { it.parameterTypes.isEmpty() && it.returnType == "Landroid/view/View;" }
            ?: throw PatchException("$holder has no single root view getter")
        holderClass.methods.add(
            ImmutableMethod(
                holder, "uaHideTabs", listOf(ImmutableMethodParameter(holder, null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        sget-boolean v0, $SHAPES->HIDE_TABS:Z
                        if-eqz v0, :tabs_done
                        invoke-virtual { p0 }, $holder->${viewGetter.name}()Landroid/view/View;
                        move-result-object v0
                        if-eqz v0, :tabs_done
                        const/16 v1, 0x8
                        invoke-virtual { v0, v1 }, Landroid/view/View;->setVisibility(I)V
                        :tabs_done
                        return-void
                    """,
                )
            },
        )
        val stored = (code[match.instructionMatches[1].index] as OneRegisterInstruction).registerA
        match.method.addInstructions(
            match.instructionMatches.last().index + 1,
            "invoke-static/range { v$stored .. v$stored }, $holder->uaHideTabs($holder)V",
        )
    }
}
