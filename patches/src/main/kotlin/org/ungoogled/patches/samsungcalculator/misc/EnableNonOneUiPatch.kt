package org.ungoogled.patches.samsungcalculator.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsungcalculator.SamsungCalculatorConstants.COMPATIBILITY_SAMSUNG_CALCULATOR

internal const val SAMSUNG_CALCULATOR_EXTENSION = "extensions/samsungcalculator.mpe"

/** Some icons are SPR files, Samsung's vector format that only One UI can draw: they become vector drawables. */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        java.util.logging.Logger.getLogger("SamsungCalculator").info("SPR drawables: $converted")
        // Measured against 12.4.00.13: 3 files.
        if (converted == 0) throw PatchException("no SPR drawables found")
    }
}

/** Sends Calculator's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_CALCULATOR_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers(
            "Lorg/ungoogled/samsungcalculator/OneUi;", "Lorg/ungoogled/samsungcalculator/Sem;",
        )
        java.util.logging.Logger.getLogger("SamsungCalculator").info("One UI members: $calls calls, $fields fields")
        // Measured against 12.4.00.13: 24 calls, 4 field accesses.
        if (calls < 10) throw PatchException("only $calls One UI calls found")
    }
}

/**
 * Off One UI Calculator dies at once on SemFloatingFeature (a One UI class). The extension carries stand-ins for the 7
 * One UI classes it calls; on a Samsung phone the framework's own classes load first, so they never run there.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Calculator usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_CALCULATOR)
    dependsOn(sprDrawablesPatch, oneUiMembersPatch)
    extendWith(SAMSUNG_CALCULATOR_EXTENSION)
}
