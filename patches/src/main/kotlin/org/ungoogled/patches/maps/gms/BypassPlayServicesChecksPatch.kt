package org.ungoogled.patches.maps.gms

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS

@Suppress("unused")
val bypassPlayServicesChecksPatch = bytecodePatch(
    name = "Bypass Play Services checks",
    description = "Makes Maps' bundled Play services signature and availability checks always " +
        "pass, so it runs re-signed and with Play services disabled or absent.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

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
    }
}
