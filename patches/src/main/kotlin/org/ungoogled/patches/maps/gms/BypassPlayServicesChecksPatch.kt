package org.ungoogled.patches.maps.gms

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val bypassPlayServicesChecksPatch = bytecodePatch(
    name = "Bypass Play Services checks",
    description = "Makes Maps' bundled Play services signature and availability checks always pass, so it " +
        "runs re-signed and with Play services disabled or absent, and lets it load tiles, search and " +
        "routing by sending Google's own package and certificate in the identity headers the Maps backend " +
        "checks. Where Play services rejects the re-signed app, Maps degrades instead of crashing. Also skips " +
        "Google's \"isn't Play Protect certified\" page, and with no Play services at all uses Android's own " +
        "Cronet so the map still loads.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // Restore map data used to be a patch of its own; a re-signed Maps needs both.
    dependsOn(restoreMapDataPatch, sharedExtensionPatch)

    execute {
        // "Is this package signed by Google?" -> true. Both methods are static
        // with locals to spare, so v0 is free.
        PlayServicesSignatureCheckFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )

        // isGooglePlayServicesAvailable -> 0, ConnectionResult.SUCCESS. This is
        // the check that gates the "Enable Google Play services" dialog; the
        // decompiler cannot even render its body, so the whole thing is
        // short-circuited rather than reasoned about branch by branch.
        PlayServicesAvailabilityFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return v0
            """,
        )

        // Google's device-compliance verdict (issue #70): never acted on, so an uncertified phone is not shut
        // out of Maps. Only the verdict's two timing metrics are skipped with it.
        DeviceComplianceVerdictFingerprint.method.addInstructions(0, "return-void")

        // No Play services at all (issue #69): Cronet's provider list then holds only its plain-Java fallback,
        // which the map's data channel cannot run on, and the map stayed empty. Android's own Cronet joins the
        // list under "score-based provider selection"; that flag now reads as on when only the fallback is there.
        val cronet = mutableClassDefBy("Lorg/chromium/net/CronetProvider;").methods.singleOrNull {
            it.name == "shouldUseScoreBasedProviderSelection" && it.returnType == "Z" &&
                it.parameterTypes.map { t -> t.toString() } == listOf("Landroid/content/Context;")
        } ?: throw PatchException("Cronet's provider selection flag not found")
        cronet.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, Lorg/ungoogled/ui/CronetChoice;->onlyFallback(Landroid/content/Context;)Z
                move-result v0
                if-eqz v0, :cronet_flag
                const/4 v0, 0x1
                return v0
            """,
            ExternalLabel("cronet_flag", cronet.implementation!!.instructions.first()),
        )
    }
}
