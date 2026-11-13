package org.ungoogled.patches.maps.ui.region

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.applicationStartHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/**
 * A batch of map tile ("paint") requests. Every batch carries the language and the region
 * the server draws the tiles for -- place names and borders, like `hl` and `gl` on the web:
 * `this.language = Locale.getDefault().toLanguageTag(); this.region = legalCountry.get()`.
 */
private object TileBatchFingerprint : Fingerprint(
    name = "<init>",
    filters = listOf(string("No tile requests specified when creating a batch request")),
)

@Suppress("unused")
val mapRegionPatch = bytecodePatch(
    name = "Map region",
    description = "Adds a Customization option to pick the country the map's names and borders are shown for.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // MAP_REGION is read at process start and at every Activity attach.
    dependsOn(sharedExtensionPatch, activityContextHookPatch, applicationStartHookPatch)

    execute {
        markPatched("mapRegionPatched")

        TileBatchFingerprint.method.let { init ->
            val insns = init.implementation!!.instructions.toList()
            fun ref(i: Int) = (insns[i] as? ReferenceInstruction)?.reference as? MethodReference
            val language = insns.indices.singleOrNull { ref(it)?.let { r -> r.definingClass == "Ljava/util/Locale;" && r.name == "toLanguageTag" } == true }
                ?: throw PatchException("the tile batch no longer takes the language from the locale")
            // The region: the next String getter that is not the locale's.
            val region = (language + 1 until insns.size).firstOrNull {
                insns[it].opcode == Opcode.INVOKE_VIRTUAL && ref(it)?.let { r ->
                    r.returnType == "Ljava/lang/String;" && r.parameterTypes.isEmpty() && r.definingClass != "Ljava/util/Locale;"
                } == true
            } ?: throw PatchException("the tile batch's region getter not found after its language")
            if (insns[region + 1].opcode != Opcode.MOVE_RESULT_OBJECT || insns[region + 2].opcode != Opcode.IPUT_OBJECT) {
                throw PatchException("the tile batch no longer stores the region it reads")
            }
            val value = (init.getInstruction(region + 1) as OneRegisterInstruction).registerA
            init.addInstructions(
                region + 2,
                """
                    invoke-static { v$value }, $SHAPES->tileRegion(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$value
                """,
            )
        }
    }
}
