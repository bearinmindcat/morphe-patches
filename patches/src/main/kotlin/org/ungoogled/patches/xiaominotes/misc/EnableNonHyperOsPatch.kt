package org.ungoogled.patches.xiaominotes.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.xiaomi.redirectMiuiProperties
import org.ungoogled.patches.xiaomi.redirectMiuiMembers
import org.ungoogled.patches.xiaominotes.XiaomiNotesConstants.COMPATIBILITY_XIAOMI_NOTES

internal const val XIAOMI_NOTES_EXTENSION = "extensions/xiaominotes.mpe"

private val log = java.util.logging.Logger.getLogger("XiaomiNotes")

private const val START_ACTIVITY = "Landroid/content/Context;->startActivity(Landroid/content/Intent;)V"

/** Logged by both reminder receivers (notes and tasks) when opening the alarm screen fails. */
private const val FALLBACK_LOG = "handleAlarm: startActivity failed, fallback to fullScreenIntent"

/** The manifest requires micloud-sdk, a HyperOS library: without it Android refuses the install. */
private val optionalLibrariesPatch = resourcePatch(
    description = "Lets Xiaomi Notes install without HyperOS's libraries.",
) {
    execute {
        val required = makeLibrariesOptional()
        log.info("Required libraries made optional: $required")
        // Measured against 3.2.7.9: micloud-sdk.
        if (required < 1) throw PatchException("no required uses-library found")
    }
}

/** Sends Notes' uses of HyperOS's additions to Android classes through Miui (see redirectMiuiMembers). */
private val miuiMembersPatch = bytecodePatch(
    description = "Answers HyperOS's additions to Android classes where the phone has none.",
) {
    extendWith(XIAOMI_NOTES_EXTENSION)

    execute {
        val (calls, fields) = redirectMiuiMembers(
            "Lorg/ungoogled/xiaominotes/Miui;", "Lorg/ungoogled/xiaominotes/MiuiShims;",
        )
        log.info("HyperOS members: $calls calls, $fields fields")
        // Measured against 3.2.7.9: 5 calls (miuix's ActivityOptions transitions).
        if (calls < 5) throw PatchException("only $calls HyperOS calls found")
    }
}

/** Off HyperOS miuix takes Notes for China's build (see redirectMiuiProperties). */
private val globalBuildPatch = bytecodePatch(
    description = "Runs Xiaomi Notes as Xiaomi's Global build.",
) {
    extendWith(XIAOMI_NOTES_EXTENSION)

    execute {
        val reads = redirectMiuiProperties("Lorg/ungoogled/xiaominotes/MiuiProps;")
        log.info("System property reads: $reads")
        // Measured against 3.2.7.9: 11, among them miuix.core.util.SystemProperties.get.
        if (reads < 1) throw PatchException("no system property reads found")
    }
}

/**
 * A due reminder opens the alarm screen from the alarm broadcast, which Android silently blocks for an app that is not
 * preinstalled; Notes only falls back to its full-screen notification when startActivity throws. The two reminder
 * receivers' startActivity calls go through Reminders, which throws where Android would block the start.
 */
private val reminderAlertPatch = bytecodePatch(
    description = "Shows due reminders as a notification when Android blocks the reminder screen.",
) {
    extendWith(XIAOMI_NOTES_EXTENSION)

    execute {
        fun Method.logsFallback() = implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string?.startsWith(FALLBACK_LOG) == true
        } == true

        val sites = mutableListOf<Pair<String, Method>>()
        classDefForEach { c -> for (m in c.methods) if (m.logsFallback()) sites += c.type to m }
        var moved = 0
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            mutable.implementation!!.instructions.toList().forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@forEachIndexed
                if (((instruction as ReferenceInstruction).reference as MethodReference).toString() != START_ACTIVITY) return@forEachIndexed
                val call = instruction as FiveRegisterInstruction
                mutable.replaceInstruction(
                    index,
                    "invoke-static { v${call.registerC}, v${call.registerD} }, " +
                        "Lorg/ungoogled/xiaominotes/Reminders;->startActivity(Landroid/content/Context;Landroid/content/Intent;)V",
                )
                moved++
            }
        }
        log.info("Reminder screen starts: $moved in ${sites.map { it.first }}")
        // Measured against 3.2.7.9: NoteAlarmReceiver.onReceive and TodoReminderHelper.handleAlarm, one call each.
        if (moved < 2) throw PatchException("reminder receivers not found ($moved calls in ${sites.map { it.first }})")
    }
}

/**
 * Notes bundles JSON.org's reference library including its Test class, whose main() calls the library's own
 * JSONStringer; on Android the platform's org.json.JSONStringer loads instead, so ART rejects the whole class. Nothing
 * runs it (it is rejected in the unpatched app too); main() now returns at once, so the class verifies.
 */
private val jsonTestClassPatch = bytecodePatch(
    description = "Keeps a bundled library's unused test class from failing verification.",
) {
    execute {
        val main = mutableClassDefByOrNull("Lorg/json/Test;")?.methods?.firstOrNull {
            it.name == "main" && it.parameterTypes.map { p -> p.toString() } == listOf("[Ljava/lang/String;")
        } ?: throw PatchException("org.json.Test.main not found")
        main.addInstruction(0, "return-void")
    }
}

/**
 * Off HyperOS Notes is refused at install (micloud-sdk) and then dies at once on miui.os.Build (a HyperOS class). The
 * extension carries stand-ins for the 79 HyperOS classes it uses (generated by .github/scripts/xiaomi-standins); on a
 * Xiaomi phone the framework's own classes load first, so they never run there. MiCloud sync, the Xiaomi account,
 * private notes (MIUI's privacy password) and the AI features need Xiaomi's system apps and stay off.
 * Generated with gen.py's default --only plus MIUI's other android.* classes Notes uses:
 * `|android/media/ExtraRingtoneManager|android/os/updater/|android/provider/SystemSettings|android/security/ChooseLockSettingsHelper`.
 */
@Suppress("unused")
val enableNonHyperOsPatch = bytecodePatch(
    name = "Enable non-HyperOS ROM support",
    description = "Makes Xiaomi Notes installable and usable on non-Xiaomi ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_XIAOMI_NOTES)
    dependsOn(optionalLibrariesPatch, miuiMembersPatch, globalBuildPatch, reminderAlertPatch, jsonTestClassPatch)
    extendWith(XIAOMI_NOTES_EXTENSION)
}
