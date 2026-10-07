package org.ungoogled.patches.maps.misc

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.maps.microg.MicrogSelection
import org.w3c.dom.Element

/**
 * Google libraries bundled into Maps that register with Play services' flag
 * service (Phenotype): logging (Clearcut), performance monitoring (Primes),
 * usage reporting, surveys, on-device data downloads, and the Location History
 * / activity-controls family. Deregistering a package disables the feature its
 * flags gate. Each registers through up to three manifest meta-data keys, and
 * some ship an asset blob as well.
 */
private val PHENOTYPE_PACKAGES = listOf(
    "com.google.android.gms.clearcut_client",
    "com.google.android.libraries.performance.primes",
    "com.google.android.gms.usagereporting",
    "com.google.android.gms.device_performance",
    "com.google.android.libraries.surveys",
    "com.google.android.libraries.impress",
    "com.google.android.gms.icing_mdd",
    "com.google.android.gms.ulr",
    "com.google.android.libraries.personalization.footprints",
    "com.google.android.libraries.consent_flows.footprints",
    "com.google.android.libraries.consent_flows.location",
    "com.google.android.libraries.mdi.sync",
    "com.google.android.libraries.gmscore.semanticlocation.device",
)

/**
 * Kept registered in microG Maps (Add microG support), which signs in: Location History / location
 * sharing (ulr), activity controls (footprints), location consent, account sync and
 * the on-device Timeline. Deregistered, their features are switched off for a
 * signed-in account too.
 */
private val ACCOUNT_PACKAGES = setOf(
    "com.google.android.gms.ulr",
    "com.google.android.libraries.personalization.footprints",
    "com.google.android.libraries.consent_flows.footprints",
    "com.google.android.libraries.consent_flows.location",
    "com.google.android.libraries.mdi.sync",
    "com.google.android.libraries.gmscore.semanticlocation.device",
)

/** Primes' crash-upload receiver and the on-device federated-learning services. */
private val COMPONENTS = listOf(
    "com.google.android.libraries.performance.primes.transmitter.LifeboatReceiver",
    "com.google.android.gms.learning.internal.training.InAppTrainingService",
    "com.google.android.gms.learning.internal.training.InAppJobService",
    "com.google.android.gms.learning.examplestoreimpl.defaultimpl.DefaultExampleStoreService",
    "com.google.android.gms.learning.examplestoreimpl.defaultimpl.DefaultExampleStoreDataTtlService",
    "com.google.android.gms.learning.internal.BackgroundProcessDetectorContentProvider",
)

private val PHENOTYPE_KEY = Regex(
    """com\.google\.android\.gms\.phenotype\.(?:heterodyne_info\.binarypb|registration\.binarypb|registration\.xml):(.+)""",
)

/** The key's spelling of a package does not always match the list's (consent.flows / consent_flows). */
private fun normalised(name: String) = name.replace(Regex("[._]+"), ".").trim('.').lowercase()

/**
 * Deregisters Google's data-collection libraries from Phenotype and removes
 * the Primes and federated-learning components. Measured against this Maps
 * version: 30 registration keys, 11 asset blobs, 6 components; anything else
 * means the app changed shape and the patch refuses to guess.
 */
internal val telemetryRegistrationsPatch = resourcePatch(
    description = "Deregisters Google's data-collection libraries and removes their components.",
) {
    execute {
        val microg = MicrogSelection.builds(this, "Remove telemetry")
        val deregistered = if (microg) PHENOTYPE_PACKAGES.filter { it !in ACCOUNT_PACKAGES } else PHENOTYPE_PACKAGES
        val wanted = deregistered.map(::normalised).toSet()
        var keys = 0
        var components = 0
        document("AndroidManifest.xml").use { manifest ->
            // Searched document-wide: most registration keys are not children of
            // <application> but of a disabled PhenotypeMetadataHolderService.
            fun all(tag: String) = manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            val doomed = all("meta-data").filter { element ->
                PHENOTYPE_KEY.matchEntire(element.getAttribute("android:name"))?.groupValues?.get(1)?.let { normalised(it) in wanted } == true
            } + listOf("activity", "service", "receiver", "provider").flatMap(::all).filter {
                it.getAttribute("android:name") in COMPONENTS
            }
            for (element in doomed) {
                if (element.tagName == "meta-data") keys++ else components++
                element.parentNode.removeChild(element)
            }
        }

        var assets = 0
        for (pkg in deregistered) {
            val path = "assets/phenotype/${pkg.replace('.', '_')}_package_metadata.binarypb"
            if (!get(path).exists()) continue
            delete(path)
            assets++
        }

        // 17 keys and 6 assets of those belong to the packages microG Maps deregisters.
        val (expectedKeys, expectedAssets) = if (microg) 17 to 6 else 30 to 11
        if (keys != expectedKeys || assets != expectedAssets || components != 6) {
            throw PatchException("expected $expectedKeys registration keys, $expectedAssets assets and 6 components; found $keys, $assets and $components")
        }
    }
}
