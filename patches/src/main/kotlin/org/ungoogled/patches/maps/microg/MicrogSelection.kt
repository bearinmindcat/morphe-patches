package org.ungoogled.patches.maps.microg

import app.morphe.patcher.patch.PatchException
import java.util.Collections
import java.util.WeakHashMap

/**
 * Whether "Add microG support" is part of the patching run a patch is in.
 *
 * The patches it changes -- the removals that take the Google account out, telemetry,
 * and the package and app name -- ask with their own context object: Morphe hands every
 * patch of one run the same bytecode and resource contexts, so a run never sees another
 * run's choice, even in a Manager that patches several apps in one process.
 *
 * Morphe runs patches in name order, dependencies first, and "Add microG support" sorts
 * ahead of every patch that asks. Should that ever change, one of them would run before
 * the choice is known; each notes itself here when it goes the Ungoogled Maps way, and
 * the microG patch fails the build when it finds one, instead of shipping microG Maps
 * with its account taken out.
 */
internal object MicrogSelection {
    private val selected: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    private val ungoogled = WeakHashMap<Any, MutableList<String>>()

    /** From the microG patch and its manifest half: this run builds microG Maps. */
    fun select(context: Any) {
        val earlier = ungoogled[context]
        if (!earlier.isNullOrEmpty()) throw PatchException(
            "Add microG support has to run before ${earlier.distinct().joinToString()}, which ran first and " +
                "built Ungoogled Maps instead. Turn those off and patch again."
        )
        selected += context
    }

    /** For a patch microG Maps does its own way: true when this run builds microG Maps. */
    fun builds(context: Any, patch: String): Boolean {
        if (context in selected) return true
        ungoogled.getOrPut(context) { mutableListOf() } += patch
        return false
    }

    /** For a patch microG Maps leaves out: true when it must leave the app alone. */
    fun replaces(context: Any, patch: String) = builds(context, patch)
}
