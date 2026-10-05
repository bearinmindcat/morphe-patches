package org.ungoogled.patches.maps.placesheet

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

/**
 * The header's carousel getter, `return shown ? holder.carousel : null`. Its only
 * look-alike in the class tests with if-eqz and reads one field instead of two.
 */
private val CAROUSEL_GETTER = listOf(
    Opcode.IGET_BOOLEAN, Opcode.IF_NEZ, Opcode.CONST_4, Opcode.RETURN_OBJECT,
    Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT,
)

@Suppress("unused")
val hideDirectoryCarouselPatch = bytecodePatch(
    name = "Hide suggestions",
    description = "Hides the row of businesses under an address on its place sheet: a preview of the " +
        "address's Directory (the restaurants, shops and offices at that address). The Directory button " +
        "still lists them. Can be switched off on the Customization screen.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // HIDE_DIRECTORY is refreshed from the Customization switch at every Activity attach.
    dependsOn(sharedExtensionPatch, activityContextHookPatch)

    execute {
        markPatched("hideDirectoryPatched")

        val header = placeSheetHeaderType()

        // A null carousel is what the header gets for a place without a directory, so
        // the sheet lays out exactly as it does there. The Directory button is untouched.
        val getter = mutableClassDefBy(header).methods.filter { m ->
            m.parameterTypes.isEmpty() && m.returnType.startsWith("L") &&
                m.implementation?.instructions?.map { it.opcode } == CAROUSEL_GETTER
        }.singleOrNull() ?: throw PatchException("directory carousel getter not found in $header")
        // v0 is its one local, overwritten by its own first instruction.
        if (getter.implementation!!.registerCount < 2) throw PatchException("carousel getter has no local register")
        getter.addInstructionsWithLabels(
            0,
            """
                sget-boolean v0, $SHAPES->HIDE_DIRECTORY:Z
                if-eqz v0, :show_directory
                const/4 v0, 0x0
                return-object v0
            """,
            ExternalLabel("show_directory", getter.implementation!!.instructions.first()),
        )
    }
}
