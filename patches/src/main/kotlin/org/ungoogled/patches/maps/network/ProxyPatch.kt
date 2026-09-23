package org.ungoogled.patches.maps.network

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.applicationStartHookPatch
import org.ungoogled.patches.maps.ui.customization.customizationScreenPatch
import org.ungoogled.patches.maps.ui.markPatched
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

private const val ACTIVITY = "org.ungoogled.ui.ProxyActivity"
private const val CRONET_PROXY = "Lorg/ungoogled/ui/CronetProxy;"
/** Cronet's public API, which Maps bundles unobfuscated. */
private const val CRONET_BUILDER = "Lorg/chromium/net/CronetEngine\$Builder;"

/** Declares the Proxy screen; it is opened by explicit class name from the Customization screen only. */
private val proxyManifestPatch = resourcePatch(
    description = "Declares the Proxy screen.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:label", "Proxy")
            activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.DayNight")
            application.appendChild(activity)
        }
    }
}

/**
 * Maps' traffic goes through two stacks. Its plain Java HTTP calls follow the
 * JVM proxy properties, which the extension sets when each process starts.
 * Cronet -- tiles, search, place data, photos -- does not keep to those: it
 * follows the system's own proxy setting and reaches Google over QUIC, so with
 * the properties alone most of Maps' traffic went direct (measured). Every
 * engine build goes through CronetEngine.Builder.buildExperimental(), and that
 * is where the extension hands the engine the proxy through Cronet's own
 * ProxyOptions, with no direct fallback, and turns QUIC off.
 */
@Suppress("unused")
val proxyPatch = bytecodePatch(
    name = "Proxy",
    description = "Adds a Proxy screen to Customization that sends Maps' own traffic, map data included, " +
        "through an HTTP proxy -- for example Orbot's (127.0.0.1:8118) to use Tor. Map data never falls " +
        "back to a direct connection: if the proxy stops, Maps stops loading. Needs a recent Play services " +
        "network engine (Cronet); Maps warns when it cannot take the proxy.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, applicationStartHookPatch, activityContextHookPatch, customizationScreenPatch, proxyManifestPatch)

    execute {
        markPatched("proxyPatched")

        val build = mutableClassDefBy(CRONET_BUILDER).methods.singleOrNull {
            it.name == "buildExperimental" && it.parameterTypes.isEmpty()
        } ?: throw PatchException("CronetEngine.Builder.buildExperimental() not found")
        val registers = build.implementation!!.registerCount
        if (registers > 16) throw PatchException("buildExperimental() uses $registers registers; p0 is out of invoke range")
        build.addInstructions(0, "invoke-static { p0 }, $CRONET_PROXY->onBuild(Ljava/lang/Object;)V")
    }
}
