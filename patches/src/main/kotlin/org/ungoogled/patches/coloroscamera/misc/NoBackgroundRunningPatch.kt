package org.ungoogled.patches.coloroscamera.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.ungoogled.patches.coloroscamera.ColorOsCameraConstants.COMPATIBILITY_COLOROS_CAMERA
import org.ungoogled.patches.shared.addInstructionsAtLabel

internal const val COLOROS_CAMERA_EXTENSION = "extensions/coloroscamera.mpe"

private const val BACKGROUND = "Lorg/ungoogled/coloroscamera/Background;"

/** The processing service ("APS"). A manifest component, so its name survives R8; so did its members, in every build checked. */
private const val APS_SERVICE = "Lcom/oplus/camera/aps/service/ApsService;"

private const val START_NOT_STICKY = 2

/**
 * The service's delayed "leave the foreground?" step, by its warning text: retried every 5 s while photos are still
 * processing, then (camera closed, queue empty, or retries used up) it keeps the foreground on 6 GB phones or leaves it.
 */
private object StopCheckFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("checkStopForegroundService, photo request still processing while "),
    custom = { _, classDef -> classDef.type == APS_SERVICE },
)

/**
 * The camera keeps its processing service (ApsService) running for good: started with every process start, also when
 * ColorOS preloads the camera, never stopped, and started again by Android after being closed (START_STICKY). Now it
 * runs while the camera is open and while photos are still processing, and stops after that; see the extension's
 * Background for each step. Only the service's lifetime changes: capture and processing are untouched.
 */
@Suppress("unused")
val colorOsCameraNoBackgroundRunningPatch = bytecodePatch(
    name = "No background running",
    description = "Stops the camera's processing service once photos are saved. Root (mount) install only.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_COLOROS_CAMERA)
    extendWith(COLOROS_CAMERA_EXTENSION)

    execute {
        val service = mutableClassDefBy(APS_SERVICE)
        fun method(name: String, returnType: String, vararg parameters: String) = service.methods.firstOrNull {
            it.name == name && it.returnType == returnType && it.parameterTypes.map(CharSequence::toString) == parameters.toList()
        } ?: throw PatchException("$APS_SERVICE->$name not found: the camera changed shape")

        // The check Background.started() runs later by name (reflection): make sure it is there.
        method("checkStopForegroundService", "V")

        // onStartCommand: schedule that check, and no START_STICKY (nothing to restart: unprocessed photos stay queued
        // for the next start and for the camera's own device-idle job).
        method("onStartCommand", "I", "Landroid/content/Intent;", "I", "I").apply {
            val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }.map { it.index }
            if (returns.isEmpty()) throw PatchException("onStartCommand has no return")
            for (index in returns.reversed()) {
                val register = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
                // replaceInstruction keeps the return's labels on the new first instruction.
                replaceInstruction(index, "const/16 v$register, $START_NOT_STICKY")
                addInstructions(index + 1, "return v$register")
            }
            addInstructions(0, "invoke-static { p0 }, $BACKGROUND->started(Landroid/app/Service;)V")
        }

        // A check still scheduled for a destroyed instance is dropped.
        method("onDestroy", "V").addInstructions(0, "invoke-static { p0 }, $BACKGROUND->destroyed(Landroid/app/Service;)V")

        // Started again when the camera's preview comes up and when the camera closes, as stock, so it can't be cut
        // off mid-photo. (checkStartForeground runs at every preview start; onStop when the camera screen stops.)
        for (name in listOf("checkStartForeground", "onStop")) {
            method(name, "V").addInstructions(0, "invoke-static { p0 }, $BACKGROUND->keepStarted(Landroid/app/Service;)V")
        }

        // The stop check, where the "still processing" retry is over: every path into the 6 GB keep-foreground test
        // (its first instruction) now also offers Background.idle() the chance to stop the service.
        StopCheckFingerprint.method.apply {
            val instructions = implementation!!.instructions.toList()
            val index = instructions.indexOfFirst {
                it.opcode == Opcode.SGET_BOOLEAN &&
                    ((it as ReferenceInstruction).reference as FieldReference).let { field ->
                        field.definingClass == APS_SERVICE && field.name == "is6GMemoryProject"
                    }
            }
            if (index < 0) throw PatchException("stop check: no is6GMemoryProject test")
            // The test overwrites its register, so the register is free just before it.
            val free = (instructions[index] as OneRegisterInstruction).registerA
            // Busy = photos (and, in newer builds, videos) still processing.
            val busy = service.methods.firstOrNull {
                it.name == "hasPhotoOrVideoProcessing" && it.returnType == "Z" && it.parameterTypes.isEmpty()
            }?.let { "invoke-direct { p0 }, $APS_SERVICE->hasPhotoOrVideoProcessing()Z" }
                ?: method("isPhotoRequestProcessing", "Z").let {
                    if (AccessFlags.PRIVATE.isSet(it.accessFlags)) throw PatchException("isPhotoRequestProcessing is private")
                    "invoke-virtual { p0 }, $APS_SERVICE->isPhotoRequestProcessing()Z"
                }
            addInstructionsAtLabel(
                index,
                """
                    $busy
                    move-result v$free
                    invoke-static { p0, v$free }, $BACKGROUND->idle(Landroid/app/Service;Z)V
                """,
            )
        }
    }
}
