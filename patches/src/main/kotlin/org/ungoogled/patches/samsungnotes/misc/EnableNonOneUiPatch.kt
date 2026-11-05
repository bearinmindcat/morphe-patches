package org.ungoogled.patches.samsungnotes.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsungnotes.SamsungNotesConstants.COMPATIBILITY_SAMSUNG_NOTES
import org.w3c.dom.Element

internal const val SAMSUNG_NOTES_EXTENSION = "extensions/samsungnotes.mpe"

/**
 * Off One UI the libraries Samsung's system provides are missing. Notes requires one Java library,
 * com.samsung.device, which alone stops the install; it and every native library become optional, and the features
 * built on them stay off. Its system-app multiArch flag goes too, so it installs with stripped libraries.
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            var required = 0
            for (tag in listOf("uses-library", "uses-native-library")) {
                val list = manifest.getElementsByTagName(tag)
                for (i in 0 until list.length) {
                    val element = list.item(i) as Element
                    if (element.getAttribute("android:required") != "false") required++
                    element.setAttribute("android:required", "false")
                }
            }
            if (required == 0) throw PatchException("no required Samsung library in the manifest")

            // A system app's multiArch: Android then demands native code for every ABI the phone runs, 32-bit too.
            // With the 32-bit libraries left out (Morphe Manager's "strip libraries"), a phone that also runs
            // 32-bit code refuses the install ("multiArch app's native libs don't support all the natively
            // supported ABIs"). Notes runs in its own process only, so its primary ABI is all it needs.
            (manifest.getElementsByTagName("application").item(0) as Element).removeAttribute("android:multiArch")
        }
    }
}

/** Sends Notes' uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_NOTES_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("Lorg/ungoogled/samsungnotes/OneUi;", "Lorg/ungoogled/samsungnotes/Sem;")
        // Measured against 4.4.48.15: 112 calls and 22 field accesses.
        if (calls < 60 || fields < 10) throw PatchException("only $calls One UI calls and $fields field accesses found")
        java.util.logging.Logger.getLogger("SamsungNotes").info("One UI members: $calls calls, $fields fields")
    }
}

/**
 * The voice recorder Notes uses off Samsung phones (the S Pen SDK's Android MediaRecorder fallback): its constructor
 * builds its recording task on a new recorder instead of itself, which builds another, until the stack overflows.
 */
private object FallbackRecorderInitFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    custom = { method, classDef ->
        method.name == "<init>" &&
            classDef.interfaces.any { it.endsWith("/MediaRecorderInterface;") } &&
            method.implementation?.instructions?.any {
                it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == classDef.type
            } == true
    },
)

/** Off Samsung phones Notes records voice with its own fallback recorder, which died on creation (StackOverflowError). */
private val voiceRecorderPatch = bytecodePatch(
    description = "Lets the fallback voice recorder start.",
) {
    execute {
        FallbackRecorderInitFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val create = instructions.indexOfFirst {
                it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == definingClass
            }
            val init = instructions.drop(create + 1).indexOfFirst {
                it.opcode == Opcode.INVOKE_DIRECT && ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                    r.definingClass == definingClass && r.name == "<init>"
                } && (it as FiveRegisterInstruction).registerC == (instructions[create] as OneRegisterInstruction).registerA
            }.let { if (it < 0) -1 else it + create + 1 }
            if (create < 0 || init < 0) throw PatchException("the fallback recorder's own construction was not found")
            val register = (instructions[create] as OneRegisterInstruction).registerA
            val self = implementation!!.registerCount - 1 // p0 of an instance method without parameters
            // The task gets this recorder instead of a new one.
            replaceInstruction(init, "nop")
            replaceInstruction(create, "move-object/from16 v$register, v$self")
        }
    }
}

/** Whether Notes' services may run without a notification: One UI 9.5 and later only (its process importance). */
private object CanWorkOnBackgroundFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(string("canWorkOnBackground, result ")),
)

/** Notes' fallback for starting a service as the current user (a hidden Android call) with plain startService. */
private object StartServiceAsUserFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
    filters = listOf(string("startServiceAsUser")),
)

private const val START_SERVICE = "startService(Landroid/content/Intent;)Landroid/content/ComponentName;"

/** Sends [method]'s Context.startService calls to Services.startService (same registers). Returns how many. */
private fun MutableMethod.guardStartService(): Int {
    val instructions = implementation!!.instructions
    var moved = 0
    for (index in instructions.indices) {
        val insn = instructions[index]
        if (insn.opcode != Opcode.INVOKE_VIRTUAL) continue
        val r = (insn as ReferenceInstruction).reference as MethodReference
        if (r.definingClass != "Landroid/content/Context;" || "${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}" != START_SERVICE) continue
        val i = insn as FiveRegisterInstruction
        replaceInstruction(
            index,
            "invoke-static { v${i.registerC}, v${i.registerD} }, Lorg/ungoogled/samsungnotes/Services;->startService(Landroid/content/Context;Landroid/content/Intent;)Landroid/content/ComponentName;",
        )
        moved++
    }
    return moved
}

/**
 * Notes runs its document and search services in the foreground with a notification, except on One UI 9.5 and later,
 * which keeps its process important instead. Off One UI that meant startForeground without a service type, which
 * Android 14 and later refuse ("Starting FGS without a type"): Notes died after a note was saved. They now run as
 * plain services, as on current One UI; a start Android refuses in the background is skipped.
 */
private val backgroundServicesPatch = bytecodePatch(
    description = "Runs Notes' services without a foreground notification.",
) {
    extendWith(SAMSUNG_NOTES_EXTENSION)

    execute {
        CanWorkOnBackgroundFingerprint.method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
        val serviceManager = mutableClassDefBy(CanWorkOnBackgroundFingerprint.classDef.type)
        var guarded = serviceManager.methods.filter { it.implementation != null }.sumOf { it.guardStartService() }
        guarded += StartServiceAsUserFingerprint.method.guardStartService()
        java.util.logging.Logger.getLogger("SamsungNotes").info("Guarded service starts: $guarded")
        // Measured against 4.4.48.15: 1 in the service manager, 4 in the start-as-user fallback.
        if (guarded < 3) throw PatchException("only $guarded service starts found")
    }
}

/**
 * Notes also calls 71 classes only One UI has (SemFloatingFeature first, at the notes list). The extension carries a
 * generated stand-in for each, with just the members Notes uses, answering "absent"; on a Samsung phone the
 * framework's own classes load first, so they never run there.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Notes installable and usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_NOTES)
    dependsOn(manifestPatch, oneUiMembersPatch, voiceRecorderPatch, backgroundServicesPatch)
    extendWith(SAMSUNG_NOTES_EXTENSION)
}
