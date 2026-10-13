package org.ungoogled.patches.waze

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val CONFIG_MANAGER = "Lcom/waze/ConfigManager;"
private const val CONFIG_VALUES = "Lcom/waze/config/ConfigValues;"
private const val OVERRIDES = "uaOverrides"

/**
 * ConfigManager.onConfigSyncedFromServer, which the native side calls each time Waze's
 * server configuration has been synced. ConfigManager is a JNI class, so its names survive.
 */
private object ConfigSyncedFromServerFingerprint : Fingerprint(
    definingClass = CONFIG_MANAGER,
    name = "onConfigSyncedFromServer",
    returnType = "V",
    parameters = emptyList(),
)

/**
 * PreferencesConfigNativeManager.onPreferencesConfigSynced: the native side calls it once the
 * server's preferences -- the values these patches change -- have synced. Unlike
 * onConfigSyncedFromServer it also runs before anyone has signed in.
 */
private object PreferencesConfigSyncedFingerprint : Fingerprint(
    definingClass = "Lcom/waze/config/PreferencesConfigNativeManager;",
    name = "onPreferencesConfigSynced",
    returnType = "V",
    parameters = emptyList(),
)

/**
 * Waze takes most of its behaviour from a configuration its server pushes down: which ads show,
 * whether speed cameras are announced, how far ahead alerts come. This adds
 * ConfigManager.uaOverrides(), run each time that configuration has been synced -- before and
 * after signing in -- and the patches
 * built on it put their own values in it -- the way Waze's own settings write them, through
 * ConfigManager's native setters -- and lock them, so the server cannot put its values back.
 *
 * The bundled `preferences` file older mods wrote these values to is no longer read: Waze
 * 5.24 copies it out of the app only after a crash loop.
 */
internal val wazeConfigOverridesPatch = bytecodePatch(
    description = "Keeps chosen Waze configuration values after every sync with Waze's server.",
) {
    // Each kept value belongs to a feature with a switch on the Customization screen.
    dependsOn(wazeCustomizationPatch)

    execute {
        val manager = mutableClassDefBy(CONFIG_MANAGER)
        manager.methods.add(
            ImmutableMethod(
                CONFIG_MANAGER, OVERRIDES, emptyList(), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(4),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        const-string v0, "UA"
                        const-string v1, "Waze configuration values kept"
                        invoke-static { v0, v1 }, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
                        return-void
                    """,
                )
            },
        )
        ConfigSyncedFromServerFingerprint.method.addInstruction(0, "invoke-static { }, $CONFIG_MANAGER->$OVERRIDES()V")
        PreferencesConfigSyncedFingerprint.method.addInstruction(0, "invoke-static { }, $CONFIG_MANAGER->$OVERRIDES()V")
    }
}

/** One of Waze's configuration values: its number, and its kind -- a flag, a number or a string. */
private class WazeConfig(val id: Int, val type: String)

/**
 * The number and kind of ConfigValues.[name]: its static initializer builds each one with
 * `new <kind>(number, category, domain, name, default)` right before storing it, and the number
 * is a constant. The numbers change from one Waze build to the next, so they are never written
 * down here.
 */
private fun BytecodePatchContext.wazeConfig(name: String): WazeConfig {
    val init = classDefBy(CONFIG_VALUES).methods.single { it.name == "<clinit>" }
    val code = init.implementation!!.instructions.toList()
    val store = code.indexOfFirst { insn ->
        insn.opcode == Opcode.SPUT_OBJECT && ((insn as ReferenceInstruction).reference as FieldReference).let {
            it.definingClass == CONFIG_VALUES && it.name == name
        }
    }
    if (store < 0) throw PatchException("Waze has no configuration value $name")
    val type = ((code[store] as ReferenceInstruction).reference as FieldReference).type
    val instance = (code[store] as OneRegisterInstruction).registerA
    // The constructor call that built it: its first argument after the instance is the number.
    val (build, idRegister) = (store - 1 downTo maxOf(0, store - 12)).firstNotNullOfOrNull { i ->
        val insn = code[i]
        val call = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: return@firstNotNullOfOrNull null
        if (call.name != "<init>" || call.parameterTypes.firstOrNull()?.toString() != "I") return@firstNotNullOfOrNull null
        val registers = when (insn) {
            is RegisterRangeInstruction -> (insn.startRegister until insn.startRegister + insn.registerCount).toList()
            is FiveRegisterInstruction -> listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
            else -> return@firstNotNullOfOrNull null
        }
        if (registers.first() != instance) return@firstNotNullOfOrNull null
        i to registers[1]
    } ?: throw PatchException("$name is not built the usual way")
    val id = (build - 1 downTo maxOf(0, build - 12)).firstNotNullOfOrNull { i ->
        val insn = code[i]
        if (insn !is OneRegisterInstruction || insn.registerA != idRegister || !insn.opcode.setsRegister()) return@firstNotNullOfOrNull null
        (insn as? NarrowLiteralInstruction)?.narrowLiteral ?: throw PatchException("$name's number is not a constant")
    } ?: throw PatchException("$name's number not found")
    return WazeConfig(id, type)
}

/**
 * Keeps Waze's configuration value [name] at [value] (a Boolean, Int or String, matching its kind),
 * re-applied after every sync with Waze's server while [feature] is switched on in Customization.
 * Needs [wazeConfigOverridesPatch].
 */
internal fun BytecodePatchContext.keepWazeConfig(feature: String, name: String, value: Any) {
    val config = wazeConfig(name)
    val (load, keeper) = when (value) {
        is Boolean -> "const/4 v3, ${if (value) 1 else 0}" to "keepBool(Ljava/lang/String;Ljava/lang/String;IZ)V"
        is Int -> "const v3, $value" to "keepInt(Ljava/lang/String;Ljava/lang/String;II)V"
        is String -> "const-string v3, \"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"" to
            "keepString(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V"
        else -> throw PatchException("unsupported value for $name: $value")
    }
    // The kind of value each of ConfigManager's own Java setters takes: setConfigValueBool(<flag kind>, Z), ...
    val javaSetter = when (value) {
        is Boolean -> "setConfigValueBool"
        is String -> "setConfigValueString"
        else -> "setConfigValueInt"
    }
    val kind = classDefBy(CONFIG_MANAGER).methods.singleOrNull { it.name == javaSetter && it.parameterTypes.size == 2 }
        ?.parameterTypes?.first()?.toString() ?: throw PatchException("ConfigManager.$javaSetter not found")
    if (config.type != kind) throw PatchException("$name is a ${config.type}, not a $kind as $javaSetter takes")
    // The extension unlocks, sets and locks it again while the feature is on -- Waze's native config
    // refuses a server update to a locked value ("config is locked. Staying with ...") -- and gives it
    // back to Waze, unlocked at Waze's own default, while it is off.
    mutableClassDefBy(CONFIG_MANAGER).methods.single { it.name == OVERRIDES }.addInstructions(
        0,
        """
            const-string v0, "$feature"
            const-string v1, "$name"
            const v2, ${config.id}
            $load
            invoke-static { v0, v1, v2, v3 }, $WAZE_CUSTOMIZATION->$keeper
        """,
    )
    declareWazeToggle(feature)
    declareWazeValue(feature, name, value)
}

/**
 * Lists [name] under [feature] on the Customization screen with the patch's [value] (a number or a
 * string; flags are the switch itself), so it can be changed in Waze (issue #50).
 */
internal fun BytecodePatchContext.declareWazeValue(feature: String, name: String, value: Any) {
    val declare = when (value) {
        is Int -> "const v2, $value" to "valueInt(Ljava/lang/String;Ljava/lang/String;I)V"
        is String -> "const-string v2, \"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\"" to
            "valueString(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"
        else -> null
    }
    if (declare != null) {
        val declared = mutableClassDefBy(WAZE_CUSTOMIZATION).methods.single { it.name == "uaDeclared" }
        // Before its return, so they are listed in the order the patches keep them.
        declared.addInstructions(
            declared.implementation!!.instructions.size - 1,
            """
                const-string v0, "$feature"
                const-string v1, "$name"
                ${declare.first}
                invoke-static { v0, v1, v2 }, $WAZE_CUSTOMIZATION->${declare.second}
            """,
        )
    }
}
