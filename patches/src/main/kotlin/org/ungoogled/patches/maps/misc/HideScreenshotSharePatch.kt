package org.ungoogled.patches.maps.misc

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

private const val SCREEN_CAPTURE = "Lorg/ungoogled/ui/ScreenCapture;"
private val REGISTER_PARAMETERS = listOf("Ljava/util/concurrent/Executor;", "Landroid/app/Activity\$ScreenCaptureCallback;")

/**
 * Maps opens its share sheet the moment a screenshot is taken (Android 14's screen capture callbacks; issue #229),
 * which also leaves the system's scrolling screenshot button dead. Every registration of such a callback goes
 * through the extension instead, which skips it while hiding screenshot sharing. Unregistering is left as it is:
 * Android ignores a callback that was never registered.
 */
internal val hideScreenshotSharePatch = bytecodePatch(
    description = "Stops Maps opening its share sheet when you take a screenshot.",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // HIDE_SCREENSHOT_SHARE is refreshed from the Customization switch at every Activity attach.
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("screenshotSharePatched")

        val sites = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any { it.isRegister() } == true) sites += c.type to m
            }
        }
        if (sites.isEmpty()) throw PatchException("no registerScreenCaptureCallback call found")
        for ((type, found) in sites) {
            val method = mutableClassDefBy(type).methods.single {
                it.name == found.name && it.returnType == found.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == found.parameterTypes.map(CharSequence::toString)
            }
            val code = method.implementation!!.instructions
            for (i in code.indices.filter { code[it].isRegister() }) {
                // invoke-virtual {activity, executor, callback} -> invoke-static, same registers.
                val call = code[i]
                val static = "$SCREEN_CAPTURE->register(Landroid/app/Activity;Ljava/util/concurrent/Executor;Ljava/lang/Object;)V"
                val replacement = when (call) {
                    is RegisterRangeInstruction ->
                        "invoke-static/range { v${call.startRegister} .. v${call.startRegister + 2} }, $static"
                    is FiveRegisterInstruction -> "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, $static"
                    else -> throw PatchException("$type->${method.name}: unexpected registerScreenCaptureCallback call")
                }
                method.replaceInstruction(i, replacement)
            }
        }
    }
}

/** `activity.registerScreenCaptureCallback(Executor, ScreenCaptureCallback)` on any Activity type. */
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.isRegister(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val ref = (this as ReferenceInstruction).reference as? MethodReference ?: return false
    return ref.name == "registerScreenCaptureCallback" && ref.returnType == "V" &&
        ref.parameterTypes.map(CharSequence::toString) == REGISTER_PARAMETERS
}
