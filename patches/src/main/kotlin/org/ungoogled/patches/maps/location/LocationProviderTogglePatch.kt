package org.ungoogled.patches.maps.location

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.applicationStartHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val locationProviderTogglePatch = bytecodePatch(
    name = "Location provider toggle",
    description = "Adds a Location source choice to the Customization screen: Android's own location " +
        "providers, or Google Play services' fused provider. With Android, Play services is never asked " +
        "for a location. Play services is never used while it is missing or disabled, so location keeps " +
        "working on phones without it.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, applicationStartHookPatch, activityContextHookPatch)

    val useFusedProvider = booleanOption(
        key = "useFusedProvider",
        default = false,
        title = "Default to Play services location",
        description = "The location source Maps starts with. On: Google Play services' fused provider " +
            "whenever Play services is installed and enabled, Android's own providers otherwise. Off: " +
            "Android's own providers. Either can be changed later on the Customization screen.",
    )

    execute {
        markPatched("locationSourcePatched")
        if (useFusedProvider.value == true) markPatched("playLocationByDefault")

        // 1. The fused provider only counts as available while the chosen source
        //    is Play services -- an AND with the real availability, in the
        //    register the method already returns, so this only ever narrows the
        //    stock condition. v0 is free again after the method's first call.
        FusedLocationAvailabilityFingerprint.method.addInstructions(
            FusedLocationAvailabilityFingerprint.instructionMatches.first().index + 1,
            """
                invoke-static { }, $SHAPES->playLocation()Z
                move-result v0
                and-int/2addr p0, v0
            """,
        )

        // 2. While it is not, a location service bind goes to a package that does
        //    not exist, so nothing in Maps reaches Play services' location service.
        PlayServicesConnectionFingerprint.let { fp ->
            val index = fp.instructionMatches.last().index
            val init = fp.method.implementation!!.instructions[index]
            // Inserting in front leaves any label on the constructor call, which would skip the redirect.
            if (init.location.labels.isNotEmpty()) throw PatchException("connection descriptor constructor is a branch target")
            val call = init as Instruction35c
            val pkg = call.registerD
            val action = call.registerE
            if (pkg > 15 || action > 15) throw PatchException("connection descriptor registers out of range")
            fp.method.addInstructions(
                index,
                """
                    invoke-static { v$pkg, v$action }, $SHAPES->locationPackage(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$pkg
                """,
            )
        }
    }
}
