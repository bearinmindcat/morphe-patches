package org.ungoogled.patches.maps.ui.powersaving

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.navzoom.navigationCameraHookPatch
import org.ungoogled.patches.maps.ui.refreshrate.frameRateHookPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/** org.ungoogled.ui.PowerSaving, the extension half: stands in for Pixel's SystemUI. */
private const val POWER_SAVING = "Lorg/ungoogled/ui/PowerSaving;"

/** Maps' own power saving screen. Its name is not obfuscated. */
private const val MIN_MODE_ACTIVITY = "Lcom/google/android/apps/gmm/features/minmode/MinModeActivity;"

/**
 * Pixel's device check: whether com.android.systemui ships
 * config_minmode_enabled. False on every other phone. The resource name occurs once.
 */
private object MinModeDeviceCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(string("config_minmode_enabled")),
)

/**
 * Where Maps arms or disarms min mode: writes "minModeOn" to SystemUI's
 * min-mode provider and, when arming, hands it a binder ("minmode_binder" via
 * "setBinder"). Called when navigation starts with Power saving mode on, and
 * again when it ends, the setting goes off or the window shrinks.
 */
private object MinModeArmFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(string("minModeOn"), string("minmode_binder"), string("setBinder")),
)

/**
 * The speedometer's dump (SpeedLimitManager), which prints the speed limit it holds
 * right after reading it: "speedLimit: " + state.limit.
 */
private object SpeedLimitDumpFingerprint : Fingerprint(
    filters = listOf(string("speedLimit: "), string("currentAverageSpeed: ")),
)

@Suppress("unused")
val powerSavingModePatch = bytecodePatch(
    name = "Power saving mode",
    description = "Brings the Pixel-only power saving mode to every phone: while driving with navigation, " +
        "press the power button and Maps shows only key information such as the next turn on a black " +
        "screen. Turn it on or off in Settings > Navigation > Power saving mode. Pixels that have it " +
        "built in keep Google's own version unless Customization > Power Saving Options > Power saving " +
        "mode is turned on. Power Saving Options also has: a navigation button that opens the power saving " +
        "screen without locking the phone, switching to it by itself when idle, a speedometer on it, a " +
        "lower frame rate, and its black map in navigation or all over Maps.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // The options' switches are refreshed at every Activity attach; the navigation button,
    // idle switch and frame rate run on the shared navigation and frame rate hooks.
    dependsOn(sharedExtensionPatch, activityContextHookPatch, navigationCameraHookPatch, frameRateHookPatch)

    execute {
        markPatched("powerSavingPatched")

        // 1. Availability. Maps offers the feature only when a server flag is on AND
        //    Pixel's SystemUI has min mode; exactly one method asks both, and it is the
        //    only caller of the device check. Answering yes there shows the setting and
        //    registers the listener that arms min mode when navigation starts. The
        //    extension answers first, and a "no" from it runs Google's own check.
        val check = MinModeDeviceCheckFingerprint.method
        val callers = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                if (method.returnType != "Z" || !AccessFlags.STATIC.isSet(method.accessFlags)) continue
                val calls = method.implementation?.instructions?.any { insn ->
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    ref != null && ref.definingClass == check.definingClass && ref.name == check.name &&
                        ref.returnType == "Z" && ref.parameterTypes.isEmpty()
                } ?: false
                if (calls) callers += classDef.type to method
            }
        }
        val (ownerType, availability) = callers.singleOrNull()
            ?: throw PatchException("expected one caller of the min-mode device check, found ${callers.size}")
        mutableClassDefBy(ownerType).methods
            .single { it.name == availability.name && it.parameterTypes == availability.parameterTypes }
            .apply {
                // v0 must be a local, not a parameter: the original code runs after it.
                // Static, so the parameters are all there is (wide ones take two registers).
                val impl = implementation!!
                val parameterRegisters = parameterTypes.size + parameterTypes.count { it == "J" || it == "D" }
                if (impl.registerCount - parameterRegisters < 1) {
                    throw PatchException("min-mode availability check has no free local register")
                }
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {}, $POWER_SAVING->forceAvailable()Z
                        move-result v0
                        if-eqz v0, :google_check
                        return v0
                    """,
                    ExternalLabel("google_check", impl.instructions.first()),
                )
            }

        // 2. Arming. Let the extension know, so it can take SystemUI's part: open the
        //    power saving screen when the power button turns the screen off.
        MinModeArmFingerprint.method.addInstructions(
            0,
            "invoke-static/range { p1 .. p1 }, $POWER_SAVING->armed(Z)V",
        )

        // 3. The power saving screen wakes the display over the lock screen and keeps it
        //    on, which Pixel's SystemUI would otherwise arrange. Right after super.onCreate.
        mutableClassDefBy(MIN_MODE_ACTIVITY).methods
            .single { it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;") }
            .apply {
                // invoke-super/range here: `this` is v18, beyond the short form's reach.
                val superCall = implementation!!.instructions.indexOfFirst { insn ->
                    (insn.opcode == Opcode.INVOKE_SUPER || insn.opcode == Opcode.INVOKE_SUPER_RANGE) &&
                        ((insn as ReferenceInstruction).reference as MethodReference).name == "onCreate"
                }
                if (superCall < 0) throw PatchException("MinModeActivity.onCreate no longer calls super.onCreate")
                addInstructions(
                    superCall + 1,
                    "invoke-static/range { p0 .. p0 }, $POWER_SAVING->onMinModeCreate(Landroid/app/Activity;)V",
                )
            }

        // 4. Speedometer: the speed limit Maps' own speedometer is showing. Its dump reads
        //    the limit field just before printing "speedLimit: "; the one method that
        //    stores into that field takes (limit, units) -- the speedometer's setter, run
        //    for every location update while navigating. The extension gets both first.
        val limitField = SpeedLimitDumpFingerprint.let { fp ->
            val instructions = fp.method.implementation!!.instructions.toList()
            val label = fp.instructionMatches.first().index
            (label - 1 downTo 0).map { instructions[it] }.firstOrNull { insn ->
                insn.opcode == Opcode.IGET && ((insn as ReferenceInstruction).reference as FieldReference).type == "I"
            }?.let { (it as ReferenceInstruction).reference as FieldReference }
                ?: throw PatchException("speedometer dump no longer reads the speed limit before printing it")
        }
        val limitSetters = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (method in classDef.methods) {
                if (method.returnType != "V" || method.parameterTypes.size != 2 || method.parameterTypes[0].toString() != "I") continue
                if (AccessFlags.STATIC.isSet(method.accessFlags)) continue
                val stores = method.implementation?.instructions?.any { insn ->
                    insn.opcode == Opcode.IPUT && ((insn as ReferenceInstruction).reference as FieldReference).let { f ->
                        f.definingClass == limitField.definingClass && f.name == limitField.name && f.type == "I"
                    }
                } ?: false
                if (stores) limitSetters += classDef.type to method
            }
        }
        val (setterType, setter) = limitSetters.singleOrNull()
            ?: throw PatchException("expected one speed limit setter, found ${limitSetters.size}")
        mutableClassDefBy(setterType).methods
            .single { it.name == setter.name && it.returnType == "V" &&
                it.parameterTypes.map { t -> t.toString() } == setter.parameterTypes.map { t -> t.toString() } }
            .addInstructions(0, "invoke-static/range { p1 .. p2 }, $POWER_SAVING->speedLimit(ILjava/lang/Object;)V")

        // 5. Power saving theme everywhere: the power saving screen's black map is one of
        //    Maps' base styles (NAVIGATION_MIN_MODE, and _AUTO while driving), picked by the
        //    map's style chooser when its "min mode" flag is set -- which MinModeActivity
        //    does while it shows. The flag goes through the extension on its way into that
        //    decision. Found from the style enum's own constant names: the chooser is the one
        //    method returning that enum which reads both min-mode constants.
        val styles = mutableListOf<Triple<String, String, String>>()   // enum, NAVIGATION_MIN_MODE, _AUTO
        classDefForEach { classDef ->
            val clinit = classDef.methods.firstOrNull { it.name == "<clinit>" } ?: return@classDefForEach
            val instructions = clinit.implementation?.instructions?.toList() ?: return@classDefForEach
            fun constantAfter(name: String): String? {
                val at = instructions.indexOfFirst { insn ->
                    (insn.opcode == Opcode.CONST_STRING || insn.opcode == Opcode.CONST_STRING_JUMBO) &&
                        ((insn as ReferenceInstruction).reference as StringReference).string == name
                }
                if (at < 0) return null
                val store = instructions.drop(at).firstOrNull { insn ->
                    insn.opcode == Opcode.SPUT_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).let { f ->
                        f.definingClass == classDef.type && f.type == classDef.type
                    }
                } ?: return null
                return ((store as ReferenceInstruction).reference as FieldReference).name
            }
            val minMode = constantAfter("NAVIGATION_MIN_MODE") ?: return@classDefForEach
            val minModeAuto = constantAfter("NAVIGATION_MIN_MODE_AUTO") ?: return@classDefForEach
            styles += Triple(classDef.type, minMode, minModeAuto)
        }
        val choosers = mutableListOf<Pair<Triple<String, String, String>, com.android.tools.smali.dexlib2.iface.Method>>()
        classDefForEach { classDef ->
            for (method in classDef.methods) {
                if (method.parameterTypes.isNotEmpty()) continue
                val style = styles.firstOrNull { it.first == method.returnType } ?: continue
                val reads = method.implementation?.instructions?.mapNotNull { insn ->
                    if (insn.opcode != Opcode.SGET_OBJECT) null
                    else ((insn as ReferenceInstruction).reference as FieldReference).takeIf { it.definingClass == style.first }?.name
                }.orEmpty()
                if (style.second in reads && style.third in reads) choosers += style to method
            }
        }
        val (style, chooserMethod) = choosers.singleOrNull()
            ?: throw PatchException("expected one map style chooser, found ${choosers.size}")
        mutableClassDefBy(chooserMethod.definingClass).methods
            .single { it.name == chooserMethod.name && it.parameterTypes.isEmpty() && it.returnType == style.first }
            .apply {
                // `if (minMode) return driving ? NAVIGATION_MIN_MODE_AUTO : NAVIGATION_MIN_MODE`:
                // the two tests right before the _AUTO constant are driving, then min mode,
                // and both flags are read from fields just before the first test.
                val instructions = implementation!!.instructions.toList()
                val auto = instructions.indexOfFirst { insn ->
                    insn.opcode == Opcode.SGET_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).let { f ->
                        f.definingClass == style.first && f.name == style.third
                    }
                }
                val tests = (auto - 1 downTo 0).filter { instructions[it].opcode == Opcode.IF_EQZ }.take(2)
                if (tests.size < 2) throw PatchException("map style chooser no longer tests min mode before its style")
                val driving = (instructions[tests[0]] as OneRegisterInstruction).registerA
                val minMode = (instructions[tests[1]] as OneRegisterInstruction).registerA
                fun readOf(register: Int) = (tests[1] - 1 downTo 0).firstOrNull { i ->
                    instructions[i].opcode == Opcode.IGET_BOOLEAN && (instructions[i] as TwoRegisterInstruction).registerA == register
                } ?: throw PatchException("map style chooser's flag in v$register is no longer a field read")
                if (driving == minMode) throw PatchException("map style chooser changed")
                readOf(driving)
                readOf(minMode)
                if (driving > 15 || minMode > 15) throw PatchException("map style chooser's flags are out of reach")
                // A branch landing on the test would skip whatever goes in front of it.
                if (implementation!!.instructions[tests[1]].location.labels.isNotEmpty()) {
                    throw PatchException("map style chooser's min mode test is a branch target")
                }
                // At the min mode test itself: both flags are read by then.
                addInstructions(
                    tests[1],
                    """
                        invoke-static { v$minMode, v$driving }, $POWER_SAVING->minModeStyle(ZZ)Z
                        move-result v$minMode
                    """,
                )
            }
    }
}
