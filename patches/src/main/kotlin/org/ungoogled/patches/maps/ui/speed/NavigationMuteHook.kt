package org.ungoogled.patches.maps.ui.speed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.maps.ui.sharedExtensionPatch

/** org.ungoogled.ui.NavMute, the extension half. */
private const val NAV_MUTE = "Lorg/ungoogled/ui/NavMute;"

/**
 * MuteControllerImpl's dump: Maps names the class in it and prints its mute level right after "  muteLevel: " --
 * the level every navigation sound of Maps' own goes by (Settings > Navigation > Mute state, the speaker button).
 */
private object MuteControllerDumpFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("MuteControllerImpl:"), string("  muteLevel: ")),
)

/**
 * Hands Maps' navigation mute controller to the extension, so the chimes of ours (speeding, cameras, Waze alerts)
 * keep quiet while navigation is Muted, as Maps' own sounds do (issue #168).
 */
internal val navigationMuteHookPatch = bytecodePatch(
    description = "Lets the extension's chimes follow navigation's Mute state.",
) {
    dependsOn(sharedExtensionPatch)

    execute {
        val dump = MuteControllerDumpFingerprint.method
        val controller = dump.definingClass
        val code = dump.implementation!!.instructions.toList()
        // The level: the call on the controller itself, taking nothing and giving the enum, whose result is appended
        // right after "  muteLevel: " (it is made before the label).
        val label = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "  muteLevel: " }
        val appended = code.drop(label + 1).firstOrNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.name == "append" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
            } == true
        }?.let { (it as Instruction35c).registerD } ?: throw PatchException("the mute level is no longer appended after its label")
        val level = (label downTo 1).firstNotNullOfOrNull { i ->
            if (code[i].opcode != Opcode.MOVE_RESULT_OBJECT || (code[i] as OneRegisterInstruction).registerA != appended) return@firstNotNullOfOrNull null
            ((code[i - 1] as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                code[i - 1].opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == controller &&
                    it.parameterTypes.isEmpty() && it.returnType.startsWith("L")
            }
        } ?: throw PatchException("MuteControllerImpl's mute level getter not found")
        // Read by name: the enum must still carry MUTED.
        val levels = classDefBy(level.returnType)
        val names = levels.methods.firstOrNull { it.name == "<clinit>" }?.implementation?.instructions?.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.orEmpty()
        if ("MUTED" !in names || "UNMUTED" !in names) throw PatchException("${level.returnType} is no longer the mute level enum")

        // Every controller made reports itself, right after its super constructor (`this` is reused at the end).
        val made = mutableClassDefBy(controller).methods.filter { it.name == "<init>" }
        if (made.isEmpty()) throw PatchException("MuteControllerImpl has no constructor")
        made.forEach { init ->
            val impl = init.implementation!!
            val self = impl.registerCount - init.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 } - 1
            val superInit = impl.instructions.indexOfFirst { insn ->
                insn.opcode == Opcode.INVOKE_DIRECT && (insn as? Instruction35c)?.registerC == self &&
                    ((insn as ReferenceInstruction).reference as MethodReference).name == "<init>"
            }
            if (superInit < 0) throw PatchException("MuteControllerImpl's constructor no longer calls its super constructor")
            init.addInstruction(superInit + 1, "invoke-static/range { p0 .. p0 }, $NAV_MUTE->onController(Ljava/lang/Object;)V")
        }

        // NavMute.level(controller): the level's name.
        val extension = mutableClassDefBy(NAV_MUTE)
        extension.methods.remove(extension.methods.singleOrNull { it.name == "level" } ?: throw PatchException("$NAV_MUTE->level not found"))
        extension.methods.add(
            ImmutableMethod(
                NAV_MUTE, "level", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "Ljava/lang/String;",
                AccessFlags.STATIC.value, null, null, MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        check-cast p0, $controller
                        invoke-virtual { p0 }, $controller->${level.name}()${level.returnType}
                        move-result-object v0
                        if-nez v0, :named
                        const/4 v0, 0x0
                        return-object v0
                        :named
                        invoke-virtual { v0 }, Ljava/lang/Enum;->name()Ljava/lang/String;
                        move-result-object v0
                        return-object v0
                    """,
                )
            },
        )
    }
}
