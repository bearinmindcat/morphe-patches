package org.ungoogled.patches.samsungkeyboard.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD

/** The keyboard's preference listing the languages still waiting for their predictive text download. */
private const val WAITING = "NEED_TO_DOWNLOAD_SELECTED_LANGUAGES"

private const val GET_STRING = "Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
private const val LANGUAGES = "Lorg/ungoogled/samsungkeyboard/Languages;"

/**
 * Predictive text (#202, #171) comes only from Samsung's Galaxy Store server, which refuses every phone but a Galaxy, so
 * off Galaxy phones it can't be had. The keyboard keeps a list of the languages whose prediction model is still to be
 * downloaded (every language it types, the bundled English included) and keeps asking from it: the "To get predicted
 * text ... download the predictive text model" banner on its settings page, the toolbar settings button's dot and
 * swipe typing's download prompt. Where the download can't complete (Languages.downloadsPossible), its reads of that
 * list now answer "none waiting"; the list itself and Galaxy phones are left as they are.
 */
@Suppress("unused")
val hidePredictiveTextPromptPatch = bytecodePatch(
    name = "Hide predictive text download prompt",
    description = "Stops asking to download predictive text on phones other than Galaxy.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        fun Method.namesList() = implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == WAITING
        } == true
        val readers = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            c.methods.filter { it.namesList() }.forEach { readers += c.type to it }
        }

        // Every getString in a method that names the list goes through Languages.waiting, which answers the list's
        // reads (by key, at run time) and passes every other read through.
        var reads = 0
        for ((type, method) in readers) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                if (insn.opcode != Opcode.INVOKE_INTERFACE && insn.opcode != Opcode.INVOKE_INTERFACE_RANGE) continue
                val r = (insn as ReferenceInstruction).reference as MethodReference
                if ("${r.definingClass}->${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}" != GET_STRING) continue
                val target = "$LANGUAGES->waiting(Landroid/content/SharedPreferences;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                val call = when (insn) {
                    is FiveRegisterInstruction -> "invoke-static { v${insn.registerC}, v${insn.registerD}, v${insn.registerE} }, $target"
                    is RegisterRangeInstruction -> "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + 2} }, $target"
                    else -> continue
                }
                mutable.replaceInstruction(index, call)
                reads++
            }
        }
        // Measured: the settings page's banner, the toolbar badge, swipe typing and the list's own bookkeeping.
        if (reads < 3) throw PatchException("only $reads reads of the keyboard's $WAITING list found")
        java.util.logging.Logger.getLogger("SamsungKeyboard").info("Predictive text prompt: $reads reads in ${readers.size} methods")
    }
}
