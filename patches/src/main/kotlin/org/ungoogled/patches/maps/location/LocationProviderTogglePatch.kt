package org.ungoogled.patches.maps.location

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import org.ungoogled.patches.maps.microg.microgLocationDialogPatch
import org.ungoogled.patches.maps.ui.SHAPES
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.applicationStartHookPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

/** The package MicroG-RE and ReVanced GmsCore install as. */
private const val MICROG_PACKAGE = "app.revanced.android.gms"

/** Android 11+ package visibility: Maps must be allowed to see microG to use its location service. */
private val microgVisibilityPatch = resourcePatch(
    description = "Lets Maps see microG, for its location service.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val queries = (0 until manifest.getElementsByTagName("queries").length)
                .map { manifest.getElementsByTagName("queries").item(it) as Element }
                .firstOrNull { it.parentNode == root }
                ?: manifest.createElement("queries").also { root.insertBefore(it, root.firstChild) }
            val packages = queries.getElementsByTagName("package")
            val listed = (0 until packages.length).any { (packages.item(it) as Element).getAttribute("android:name") == MICROG_PACKAGE }
            if (!listed) queries.appendChild(manifest.createElement("package").apply { setAttribute("android:name", MICROG_PACKAGE) })
        }
    }
}

@Suppress("unused")
val locationProviderTogglePatch = bytecodePatch(
    name = "Location provider toggle",
    description = "Adds a Location source choice to the Customization screen: Android's own location " +
        "providers, microG's (microg Services) or Google Play services' fused provider. With Android, " +
        "neither is ever asked for a location. A source that is missing or disabled is never used, so " +
        "location keeps working without it. Also keeps the network (Wi-Fi/cell) provider registered when no " +
        "fused provider answers, instead of GPS only, so a fix does not go stale indoors.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    // Network location fallback used to be a patch of its own.
    dependsOn(
        sharedExtensionPatch, applicationStartHookPatch, activityContextHookPatch, networkLocationFallbackPatch,
        microgVisibilityPatch, microgLocationDialogPatch,
    )

    val useFusedProvider = booleanOption(
        key = "useFusedProvider",
        default = false,
        title = "Default to Play services location",
        description = "The location source Maps starts with. On: the fused provider -- Google Play " +
            "services', or microG's in microG Maps -- whenever it is installed and enabled, Android's own " +
            "providers otherwise. Off: Android's own providers. Can be changed later on the Customization screen.",
    )

    execute {
        markPatched("locationSourcePatched")
        if (useFusedProvider.value == true) markPatched("playLocationByDefault")

        // 1. The fused provider only counts as available while the chosen source
        //    is a fused one -- an AND with the real availability, in the register
        //    the method already returns, so this only ever narrows the stock
        //    condition. v0 is free again after the method's first call.
        FusedLocationAvailabilityFingerprint.method.addInstructions(
            FusedLocationAvailabilityFingerprint.instructionMatches.first().index + 1,
            """
                invoke-static { }, $SHAPES->playLocation()Z
                move-result v0
                and-int/2addr p0, v0
            """,
        )

        // 2. Every location service connection goes to the chosen source: its package,
        //    under the name it answers (MicroG-RE renamed the service). While the source
        //    is Android, to a package that does not exist, so nothing in Maps reaches a
        //    fused provider.
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
                    invoke-static { v$pkg, v$action }, $SHAPES->locationAction(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$action
                    invoke-static { v$pkg, v$action }, $SHAPES->locationPackage(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$pkg
                """,
            )
        }
    }
}
