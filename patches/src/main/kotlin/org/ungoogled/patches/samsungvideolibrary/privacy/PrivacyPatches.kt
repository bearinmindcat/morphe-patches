package org.ungoogled.patches.samsungvideolibrary.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.samsung.hideDiagnosticsAgent
import org.ungoogled.patches.samsung.muteFeatureSurveys
import org.ungoogled.patches.samsungvideolibrary.SamsungVideoLibraryConstants.COMPATIBILITY_SAMSUNG_VIDEO_LIBRARY

private val log = java.util.logging.Logger.getLogger("SamsungVideoLibrary")

/** Samsung Analytics' native key library, the app's only native code. */
private const val ANALYTICS_LIBRARY = "libDiagMonKey.so"
private const val ANALYTICS_KEY = "Lcom/samsung/context/sdk/samsunganalytics/NativeHelper;->getSALTKey()[C"

/**
 * The analytics key library is built for 4 KB memory pages: on a phone with 16 KB pages (Pixel 8 and newer with the
 * developer option, Android's newest builds) Android warned at every start that the app is not 16 KB compatible and
 * ran it in its compatibility mode. With the tracker off nothing needs the key, so the library goes.
 */
private val analyticsLibraryPatch = resourcePatch {
    execute {
        val libraries = listApkEntries("lib/").filter { it.endsWith("/$ANALYTICS_LIBRARY") }
        if (libraries.isEmpty()) throw PatchException("$ANALYTICS_LIBRARY not found in the APK")
        libraries.forEach { delete(it) }
    }
}

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Turns off Samsung Analytics and crash reports.",
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_VIDEO_LIBRARY)
    dependsOn(analyticsLibraryPatch)

    execute {
        // Samsung Analytics (every screen and button, sent to Samsung's servers directly where Samsung's diagnostics
        // agent is missing): the tracker is never ready, so it neither starts nor sends.
        AnalyticsTrackerReadyFingerprint.method.addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        // Its hashing key came from the native library removed above: an empty key, read only if the tracker ran.
        val readers = mutableListOf<Method>()
        classDefForEach { c ->
            c.methods.filterTo(readers) { m ->
                m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r ->
                        "${r.definingClass}->${r.name}()${r.returnType}" == ANALYTICS_KEY
                    } == true
                } == true
            }
        }
        if (readers.isEmpty()) throw PatchException("no reader of Samsung Analytics' native key found")
        for (reader in readers) {
            val method = mutableClassDefBy(reader.definingClass).methods.first {
                it.name == reader.name && it.parameterTypes == reader.parameterTypes && it.returnType == reader.returnType
            }
            val instructions = method.implementation!!.instructions
            for (index in instructions.indices.reversed()) {
                val call = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if ("${call.definingClass}->${call.name}()${call.returnType}" != ANALYTICS_KEY) continue
                val result = instructions.getOrNull(index + 1)
                if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) throw PatchException("native key read without a result")
                val register = (result as OneRegisterInstruction).registerA
                method.replaceInstruction(index + 1, "new-array v$register, v$register, [C")
                method.replaceInstruction(index, "const/4 v$register, 0x0")
            }
        }

        // DiagMon (crash reports with logs): the diagnostics agent reads as not installed, the SDK's own "not
        // supported" case, so no crash handler is set and no report is made.
        val lookups = hideDiagnosticsAgent()
        log.info("Diagnostics agent lookups: $lookups")
        // Measured against 1.4.22.81: 4 (the SDK's two version checks, its policy check and the app's feature table).
        if (lookups < 2) throw PatchException("only $lookups diagnostics agent lookups found")

        // Samsung's pen SDK reports which of its features are used to One UI's context service.
        log.info("Feature survey senders: ${muteFeatureSurveys()}")
    }
}
