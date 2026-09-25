package org.ungoogled.patches.maps.ui.powersaving

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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

@Suppress("unused")
val powerSavingModePatch = bytecodePatch(
    name = "Power saving mode",
    description = "Brings the Pixel-only power saving mode to every phone: while driving with navigation, " +
        "press the power button and Maps shows only key information such as the next turn on a black " +
        "screen. Turn it on or off in Settings > Navigation > Power saving mode.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch)

    execute {
        // 1. Availability. Maps offers the feature only when a server flag is on AND
        //    Pixel's SystemUI has min mode; exactly one method asks both, and it is the
        //    only caller of the device check. Answering yes there shows the setting and
        //    registers the listener that arms min mode when navigation starts.
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
            .addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """,
            )

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
    }
}
