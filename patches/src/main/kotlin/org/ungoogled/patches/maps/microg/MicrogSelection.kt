package org.ungoogled.patches.maps.microg

import app.morphe.patcher.patch.PatchException
import java.util.WeakHashMap

/**
 * Whether "Add microG-RE support" is part of the patching run a patch is in.
 *
 * The patches it changes -- the removals that take the Google account out, telemetry,
 * and the package and app name -- ask with their own context object: Morphe hands every
 * patch of one run the same bytecode and resource contexts, so a run never sees another
 * run's choice, even in a Manager that patches several apps in one process.
 *
 * Morphe runs patches in name order, dependencies first, and "Add microG-RE support" sorts
 * ahead of every patch that asks. Should that ever change, one of them would run before
 * the choice is known; each notes itself here when it goes the Ungoogled Maps way, and
 * the microG patch fails the build when it finds one, instead of shipping microG Maps
 * with its account taken out.
 */
internal object MicrogSelection {
    /** Which microG a run builds for: microG-RE beside Play services, or original microG in its place. */
    enum class Flavor(val patch: String) { RE("Add microG-RE support"), ORIGINAL("Add original microG support") }

    private val selected = WeakHashMap<Any, Flavor>()
    private val ungoogled = WeakHashMap<Any, MutableList<String>>()

    /** From a microG patch and its manifest half: this run builds microG Maps. */
    fun select(context: Any, flavor: Flavor = Flavor.RE) {
        val earlier = ungoogled[context]
        if (!earlier.isNullOrEmpty()) throw PatchException(
            "${flavor.patch} has to run before ${earlier.distinct().joinToString()}, which ran first and " +
                "built Ungoogled Maps instead. Turn those off and patch again."
        )
        val other = selected[context]
        if (other != null && other != flavor) throw PatchException(
            "${Flavor.RE.patch} and ${Flavor.ORIGINAL.patch} cannot both be on. Pick the one for your microG."
        )
        selected[context] = flavor
    }

    /** True when this run builds for original microG, which vouches for Maps under its own package name. */
    fun original(context: Any) = selected[context] == Flavor.ORIGINAL

    /** For a patch microG Maps does its own way: true when this run builds microG Maps. */
    fun builds(context: Any, patch: String): Boolean {
        if (selected.containsKey(context)) return true
        ungoogled.getOrPut(context) { mutableListOf() } += patch
        return false
    }

    /** For a patch microG Maps leaves out: true when it must leave the app alone. */
    fun replaces(context: Any, patch: String) = builds(context, patch)
}
