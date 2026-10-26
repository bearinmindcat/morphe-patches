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

internal const val SAMSUNG_KEYBOARD_EXTENSION = "extensions/samsungkeyboard.mpe"
private const val SYSTEM_ACCESS = "Lorg/ungoogled/samsungkeyboard/SystemAccess;"

/** Classes whose calls SystemAccess can take, and the prefix of its methods for each. */
private val OWNERS = mapOf(
    "Landroid/provider/Settings\$Secure;" to "secure",
    "Landroid/provider/Settings\$System;" to "system",
    "Landroid/provider/Settings\$Global;" to "global",
    "Landroid/content/Context;" to "context",
    "Landroid/view/ViewManager;" to "viewManager",
    "Landroid/view/WindowManager;" to "windowManager",
)

/**
 * Calls only a system app may make go through SystemAccess, which does them the ordinary app's way: the copy is
 * an ordinary app, and Android refuses it the hidden settings the system keyboard reads as it starts, the
 * broadcast to every user it sends as it shows and the system window sticker suggestions open as you type. Every
 * call SystemAccess has a method for moves (an instance call passes its receiver first); nothing else does.
 */
internal val systemAccessPatch = bytecodePatch(
    description = "Lets the keyboard run without the system app's privileges.",
) {
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        // The shim's own methods, so only calls it can take are moved.
        val shims = mutableClassDefBy(SYSTEM_ACCESS).methods
            .map { Triple(it.name, it.parameterTypes.joinToString(""), it.returnType) }.toSet()

        val virtual = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
        val invokes = virtual + setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

        /** The shim's name and parameters for a call, or null when SystemAccess does not take it. */
        fun shim(opcode: Opcode, r: MethodReference): Pair<String, String>? {
            val owner = OWNERS[r.definingClass] ?: return null
            val name = owner + r.name.replaceFirstChar(Char::uppercaseChar)
            val parameters = (if (opcode in virtual) r.definingClass else "") + r.parameterTypes.joinToString("")
            return (name to parameters).takeIf { Triple(name, parameters, r.returnType) in shims }
        }

        fun calls(m: Method) = m.implementation?.instructions?.any { insn ->
            insn.opcode in invokes && shim(insn.opcode, (insn as ReferenceInstruction).reference as MethodReference) != null
        } ?: false

        val sites = mutableListOf<Pair<String, Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            classDef.methods.filter(::calls).forEach { sites += classDef.type to it }
        }

        var moved = 0
        for ((type, method) in sites) {
            val mutableMethod = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutableMethod.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                if (insn.opcode !in invokes) continue
                val r = (insn as ReferenceInstruction).reference as MethodReference
                val (name, parameters) = shim(insn.opcode, r) ?: continue
                val target = "$SYSTEM_ACCESS->$name($parameters)${r.returnType}"
                val call = when (insn) {
                    is FiveRegisterInstruction -> listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                        .take(insn.registerCount).joinToString(", ", "invoke-static {", "}, $target") { "v$it" }
                    is RegisterRangeInstruction ->
                        "invoke-static/range {v${insn.startRegister} .. v${insn.startRegister + insn.registerCount - 1}}, $target"
                    else -> continue
                }
                mutableMethod.replaceInstruction(index, call)
                moved++
            }
        }
        // Measured against 5.9.40.75 (about 500 calls); far fewer means the app changed shape.
        if (moved < 300) throw PatchException("only $moved system calls found to move")
        java.util.logging.Logger.getLogger("SamsungKeyboard").info("System access: $moved calls moved")
    }
}
