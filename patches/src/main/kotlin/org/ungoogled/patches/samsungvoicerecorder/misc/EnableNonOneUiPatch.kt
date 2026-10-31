package org.ungoogled.patches.samsungvoicerecorder.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsung.redirectSystemServices
import org.ungoogled.patches.samsungvoicerecorder.SamsungVoiceRecorderConstants.COMPATIBILITY_SAMSUNG_VOICE_RECORDER

private const val OBJECT_ANIMATOR = "Landroid/animation/ObjectAnimator;"
private const val MEDIA_RECORDER = "Landroid/media/MediaRecorder;"

/** MediaRecorder calls RecorderProgress follows, to report the recorded time as One UI's recorder does. */
private val RECORDER_CALLS = listOf(
    "setOnInfoListener(Landroid/media/MediaRecorder\$OnInfoListener;)V",
    "start()V", "pause()V", "resume()V", "stop()V", "reset()V", "release()V",
).associate { shape ->
    "$MEDIA_RECORDER->$shape" to
        "Lorg/ungoogled/samsungvoicerecorder/RecorderProgress;->${shape.substringBefore('(')}($MEDIA_RECORDER${shape.substringAfter('(')}"
}

internal const val SAMSUNG_VOICE_RECORDER_EXTENSION = "extensions/samsungvoicerecorder.mpe"

/** Some icons are SPR files, Samsung's vector format that only One UI can draw: they become vector drawables. */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        java.util.logging.Logger.getLogger("SamsungVoiceRecorder").info("SPR drawables: $converted")
        // Measured against 21.5.91.33: 10 files.
        if (converted < 5) throw PatchException("only $converted SPR drawables found")
    }
}

/** Sends Voice Recorder's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_VOICE_RECORDER_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers(
            "Lorg/ungoogled/samsungvoicerecorder/OneUi;", "Lorg/ungoogled/samsungvoicerecorder/Sem;",
        )
        val services = redirectSystemServices("Lorg/ungoogled/samsungvoicerecorder/Services;")
        java.util.logging.Logger.getLogger("SamsungVoiceRecorder")
            .info("One UI members: $calls calls, $fields fields; service lookups: $services")
        if (calls < 50 || fields < 5) throw PatchException("only $calls One UI calls and $fields field accesses found")
        if (services < 50) throw PatchException("only $services service lookups found")
    }
}

/**
 * One UI's MediaRecorder reports the recorded time every 35 ms, and Voice Recorder's timer, wave and saved length come
 * from those reports: off One UI a recording stayed at 00:00.0. RecorderProgress sends the same reports.
 */
private val recorderProgressPatch = bytecodePatch(
    description = "Reports the recorded time while recording.",
) {
    extendWith(SAMSUNG_VOICE_RECORDER_EXTENSION)

    execute {
        val moved = redirectCalls(RECORDER_CALLS)
        java.util.logging.Logger.getLogger("SamsungVoiceRecorder").info("Recorder calls: $moved")
        if (moved < 5) throw PatchException("only $moved MediaRecorder calls found")
    }
}

/**
 * The record buttons fade in and out through an ObjectAnimator that never gets its target: One UI's animator skips a
 * missing target, Android's sets the property on null, so starting a recording died (View.setAlpha on null). Each
 * such (View, boolean) method gives the animator its view before starting it.
 */
private val buttonAnimatorPatch = bytecodePatch(
    description = "Gives the record buttons' fade animations their view.",
) {
    execute {
        fun Method.calls(name: String) = implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> r.definingClass == OBJECT_ANIMATOR && r.name == name } == true
        } == true

        val sites = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (!c.type.startsWith("Lcom/sec/android/app/voicenote/")) return@classDefForEach
            for (m in c.methods) {
                if (m.parameterTypes.map { it.toString() } == listOf("Landroid/view/View;", "Z") && m.returnType == "V" &&
                    m.calls("<init>") && m.calls("setProperty") && m.calls("start") && !m.calls("setTarget")
                ) sites += c.type to m
            }
        }
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            val start = instructions.indexOfLast {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> r.definingClass == OBJECT_ANIMATOR && r.name == "start" } == true
            }
            val animator = (instructions[start] as FiveRegisterInstruction).registerC
            mutable.addInstruction(start, "invoke-virtual { v$animator, p1 }, $OBJECT_ANIMATOR->setTarget(Ljava/lang/Object;)V")
        }
        java.util.logging.Logger.getLogger("SamsungVoiceRecorder").info("Button animators: ${sites.size}")
        // Measured against 21.5.91.33: ControlButtonFactory.enableButton and ControlButtonFragmentUtils.enableButtonView.
        if (sites.isEmpty()) throw PatchException("no target-less button animator found")
    }
}

/**
 * Off One UI Voice Recorder dies at once on SemFloatingFeature (a One UI class). The extension carries stand-ins for
 * the 42 One UI classes it calls; on a Samsung phone the framework's own classes load first, so they never run there.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Voice Recorder usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_VOICE_RECORDER)
    dependsOn(sprDrawablesPatch, oneUiMembersPatch, buttonAnimatorPatch, recorderProgressPatch)
    extendWith(SAMSUNG_VOICE_RECORDER_EXTENSION)
}
