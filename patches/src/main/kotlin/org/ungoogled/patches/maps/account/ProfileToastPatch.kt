package org.ungoogled.patches.maps.account

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderSwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.maps.microg.MicrogSelection
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.customization.accountSheet
import org.ungoogled.patches.maps.ui.mapsId
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel

/** The legacy sheet's "Your profile" tap: its case in the row holder's shared onClick dispatcher, by trace label. */
private object LegacyProfileClickFingerprint : Fingerprint(
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(string("YourProfileActionSpecClicked")),
)

private fun Instruction.ref() = (this as? ReferenceInstruction)?.reference

/** The registers of an invoke, in order. */
private fun Instruction.registers(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** The register of an invoke that carries its parameter [index] (`this` not counted). */
private fun Instruction.argument(index: Int): Int {
    val ref = ref() as MethodReference
    var at = if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
    ref.parameterTypes.take(index).forEach { at += if (it == "J" || it == "D") 2 else 1 }
    return registers()[at]
}

/** The instruction index a switch in [method] jumps to for [key]. */
private fun switchTarget(method: MutableMethod, key: Int): Int {
    val payloads = method.implementation!!.instructions.filterIsInstance<BuilderSwitchPayload>()
    val element: SwitchElement = payloads.flatMap { it.switchElements }.singleOrNull { it.key == key }
        ?: throw PatchException("${method.definingClass}->${method.name}: no single switch case $key")
    return (element as com.android.tools.smali.dexlib2.builder.instruction.BuilderSwitchElement).target.location.index
}

/**
 * The modern sheet's "Your profile" row is a custom action made by one method: its title is
 * YOUR_PROFILE_SIDE_MENU_BUTTON and its click handler a lambda `new Lambda(this, case)`. Returns the
 * lambda's invoke method and the index of that case.
 */
private fun BytecodePatchContext.modernProfileClick(): Pair<MutableMethod, Int> {
    val action = CustomActionToStringFingerprint.classDef.type
    val title = mapsId(ResourceType.STRING, "YOUR_PROFILE_SIDE_MENU_BUTTON")
    val builders = Fingerprint(filters = listOf(literal(title.toLong()), newInstance(action))).matchAll()
    val builder = builders.singleOrNull()?.method ?: throw PatchException("modern \"Your profile\" row: found ${builders.size} builders")
    val code = builder.implementation!!.instructions.toList()
    val ctor = code.singleOrNull { (it.opcode == Opcode.INVOKE_DIRECT || it.opcode == Opcode.INVOKE_DIRECT_RANGE) && (it.ref() as? MethodReference)?.let { r -> r.definingClass == action && r.name == "<init>" } == true }
        ?: throw PatchException("modern \"Your profile\" row: the action is not made once")
    val ctorRef = ctor.ref() as MethodReference
    // The click handler: the field the action's toString prints after "onClick=".
    val onClick = CustomActionToStringFingerprint.let { fp ->
        fp.method.implementation!!.instructions.drop(fp.instructionMatches.last().index)
            .firstOrNull { it.opcode == Opcode.IGET_OBJECT }?.ref() as? FieldReference
    } ?: throw PatchException("the account sheet action's click handler not found")
    val handlerIndex = ctorRef.parameterTypes.map(CharSequence::toString).let { types ->
        types.indexOf(onClick.type).takeIf { it >= 0 && types.count { t -> t == onClick.type } == 1 }
    } ?: throw PatchException("modern \"Your profile\" row: the action takes no single click handler")
    val handler = ctor.argument(handlerIndex)
    val made = code.indexOfLast { i ->
        i.opcode == Opcode.INVOKE_DIRECT && (i.ref() as MethodReference).let { r ->
            r.name == "<init>" && r.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "I")
        } && i.registers().first() == handler
    }
    if (made < 0) throw PatchException("modern \"Your profile\" row: its click lambda is not made here")
    val lambda = (code[made].ref() as MethodReference).definingClass
    val caseRegister = code[made].registers()[2]
    val key = (made - 1 downTo 0).map { code[it] }.firstOrNull { (it as? OneRegisterInstruction)?.registerA == caseRegister }
        as? NarrowLiteralInstruction ?: throw PatchException("modern \"Your profile\" row: the lambda's case is not a constant")
    val invoke = mutableClassDefBy(lambda).methods.singleOrNull { m ->
        m.parameterTypes.isEmpty() && m.returnType == "Ljava/lang/Object;" && m.implementation?.instructions?.any { it is BuilderSwitchPayload } == true
    } ?: throw PatchException("$lambda: no switching invoke method")
    return invoke to switchTarget(invoke, key.narrowLiteral)
}

/**
 * Tapping "Your profile" toasts "Can't sign in" in both sheets. Each tap is one case of a shared,
 * switch-dispatched method; the new body is placed AT the case label and returns, and the old one is
 * left behind it, unreachable (removing it would merge its labels into the next case).
 */
internal val profileToastPatch = bytecodePatch(
    description = "Tapping \"Your profile\" shows a \"Can't sign in\" toast instead of opening nothing.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // Shapes.SKIP_DISMISS: with Keep account sheet open, the sheet stays up behind the toast.
    dependsOn(sharedExtensionPatch)

    execute {
        if (MicrogSelection.replaces(this, "Remove sign-in prompts")) return@execute

        // Modern sheet: the lambda's case starts by reading what it captured, `iget-object vA, vB, this.a`.
        modernProfileClick().let { (method, case) ->
            val code = method.implementation!!.instructions
            val first = code[case]
            val captured = first.ref() as? FieldReference
            if (first.opcode != Opcode.IGET_OBJECT || captured?.definingClass != method.definingClass) {
                throw PatchException("modern \"Your profile\" case does not start with its captured object")
            }
            val r = (first as TwoRegisterInstruction).registerA
            val receiver = first.registerB
            method.addInstructionsAtLabel(
                case,
                """
                    iget-object v$r, v$receiver, $captured
                    invoke-static/range { v$r .. v$r }, $SIGN_IN->cantSignIn(Ljava/lang/Object;)V
                    const/16 v$r, 0x1
                    sput-boolean v$r, $SHAPES->SKIP_DISMISS:Z
                    const/16 v$r, 0x0
                    return-object v$r
                """,
            )
        }

        // Legacy sheet: `iget-object vA, vB, this.a; move-object vC, vA; check-cast vC, Holder; iget-object ..; const-string`.
        LegacyProfileClickFingerprint.let { fp ->
            val code = fp.method.implementation!!.instructions
            val case = fp.instructionMatches.first().index - 4
            val shape = listOf(Opcode.IGET_OBJECT, Opcode.MOVE_OBJECT, Opcode.CHECK_CAST, Opcode.IGET_OBJECT)
            if (case < 0 || (0..3).any { code[case + it].opcode != shape[it] } || code[case + 2].ref().toString() != accountSheet().holder) {
                throw PatchException("legacy \"Your profile\" case is shaped differently")
            }
            val first = code[case] as TwoRegisterInstruction
            fp.method.addInstructionsAtLabel(
                case,
                """
                    iget-object v${first.registerA}, v${first.registerB}, ${code[case].ref()}
                    invoke-static/range { v${first.registerA} .. v${first.registerA} }, $SIGN_IN->cantSignIn(Ljava/lang/Object;)V
                    return-void
                """,
            )
        }
    }
}
