package org.ungoogled.patches.samsungwonderland.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsungwonderland.SamsungWonderlandConstants.COMPATIBILITY_SAMSUNG_WONDERLAND

internal const val SAMSUNG_WONDERLAND_EXTENSION = "extensions/samsungwonderland.mpe"

private const val EXT = "Lorg/ungoogled/samsungwonderland"

/** Sends Wonderland's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers). */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    extendWith(SAMSUNG_WONDERLAND_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers("$EXT/OneUi;", "$EXT/Sem;")
        java.util.logging.Logger.getLogger("Wonderland").info("One UI members: $calls calls, $fields fields")
        if (calls == 0) throw PatchException("no One UI calls found")
    }
}

/** Only the system may set a live wallpaper directly: Android's live wallpaper preview sets it (see Wallpapers). */
private val setWallpaperPatch = bytecodePatch(
    description = "Sets Wonderland's wallpapers through Android's live wallpaper preview.",
) {
    extendWith(SAMSUNG_WONDERLAND_EXTENSION)

    execute {
        val invoke = ReflectionInvokeFingerprint.method
        invoke.addInstructionsWithLabels(
            0,
            """
                invoke-static { p1, p2, p4 }, $EXT/Wallpapers;->invoke(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                sget-object v1, $EXT/Wallpapers;->NONE:Ljava/lang/Object;
                if-eq v0, v1, :stock
                return-object v0
            """,
            ExternalLabel("stock", invoke.getInstruction(0)),
        )
    }
}

/**
 * Off One UI Wonderland dies at once on SemSystemProperties. The extension carries stand-ins for the One UI classes
 * it calls, and its video layers play through Android's MediaPlayer (see SemMediaPlayer).
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Runs Wonderland on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_WONDERLAND)
    dependsOn(oneUiMembersPatch, setWallpaperPatch)
    extendWith(SAMSUNG_WONDERLAND_EXTENSION)
}
