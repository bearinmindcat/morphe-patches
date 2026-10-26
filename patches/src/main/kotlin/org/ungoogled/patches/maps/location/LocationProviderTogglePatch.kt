package org.ungoogled.patches.maps.location

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
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
    description = "Lets Maps see microG-RE, for its location service.",
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
        "providers, microG-RE's or Google Play services' fused provider. With Android, " +
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
            "services', or microG-RE's in microG Maps -- whenever it is installed and enabled, Android's own " +
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

        // 3. The heading (issue #41): Play services' fused orientation only while the source is Google's
        //    Play services. Its requests go through the same location service as the location, so with
        //    Android they went nowhere -- no beam on the blue dot, a calibration prompt that never cleared --
        //    and with microg Services they depended on microG's accuracy reports. Otherwise Maps uses its own
        //    fusion of the phone's sensors, as it does on a phone without Play services. v0 is a local.
        FusedOrientationCheckFingerprint.method.apply {
            val impl = implementation!!
            if (impl.registerCount - 1 < 1) throw PatchException("heading source check has no free local register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $SHAPES->playOrientation()Z
                    move-result v0
                    if-nez v0, :stock_check
                    return v0
                """,
                ExternalLabel("stock_check", impl.instructions.first()),
            )
        }

        // 4. The my-location button's location-settings check (issue #40). It asks Play services' SettingsClient
        //    and centres the map when the answer arrives; with Android its connection goes nowhere and, once
        //    Maps had been in the background, no answer ever came, so the button did nothing. While the source
        //    is Android it is answered from Android's own location switch (Shapes.locationSettingsAnswer),
        //    right after the request's flags are stored -- the answer method reads them.
        LocationSettingsCheckFingerprint.let { fp ->
            val method = fp.method
            val ins = method.implementation!!.instructions.toList()
            val owner = method.definingClass
            val store = ins.indexOfFirst {
                it.opcode == Opcode.IPUT_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).definingClass == owner
            }
            if (store < 0 || store + 1 >= ins.size) throw PatchException("location-settings check no longer stores its request first")
            // The answer method: the class's one private method taking Maps' enum of answers.
            val answer = mutableClassDefBy(owner).methods.singleOrNull { m ->
                m.returnType == "V" && m.parameterTypes.size == 1 && AccessFlags.PRIVATE.isSet(m.accessFlags) &&
                    runCatching { classDefBy(m.parameterTypes.first().toString()) }.getOrNull()?.let { e ->
                        e.methods.any { it.name == "<clinit>" && it.implementation?.instructions?.any { i ->
                            i.opcode == Opcode.CONST_STRING && (i as ReferenceInstruction).reference.toString() == "NOT_OPTIMIZED_OR_DISABLED"
                        } == true }
                    } == true
            } ?: throw PatchException("location-settings answer method not found")
            val results = answer.parameterTypes.first().toString()
            val context = mutableClassDefBy(owner).fields.singleOrNull { it.type == "Landroid/content/Context;" }?.name
                ?: throw PatchException("location-settings check has no single Context field")
            // v0 and v2 are dead here: the request's builder registers, re-read from the field afterwards.
            val after = ins[store + 1]
            if (after.location.labels.isNotEmpty()) throw PatchException("location-settings check: insertion point is a branch target")
            method.addInstructionsWithLabels(
                store + 1,
                """
                    iget-object v0, p0, $owner->$context:Landroid/content/Context;
                    const-class v2, $results
                    invoke-static { v0, v2 }, $SHAPES->locationSettingsAnswer(Landroid/content/Context;Ljava/lang/Class;)Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :ask_play_services
                    check-cast v0, $results
                    invoke-direct { p0, v0 }, $owner->${answer.name}($results)V
                    return-void
                """,
                ExternalLabel("ask_play_services", after),
            )
        }
    }
}
