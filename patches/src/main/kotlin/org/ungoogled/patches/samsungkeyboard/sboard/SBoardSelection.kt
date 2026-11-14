package org.ungoogled.patches.samsungkeyboard.sboard

import app.morphe.patcher.patch.PatchException
import java.util.WeakHashMap

/** The Manager's group (patch category) of the SBoard patches. */
internal const val SBOARD = "SBoard"

/**
 * Whether "Build SBoard" is part of the patching run a patch is in (as MicrogSelection for microG Maps).
 *
 * Morphe hands every patch of one run the same resource and bytecode contexts and runs patches in name order,
 * dependencies first: "Build SBoard" sorts ahead of "Change app name" and "Change package name", which leave the
 * name and package to it when it is on. Should that order ever change, they note that they ran first and "Build
 * SBoard" fails the build instead of shipping a half-renamed keyboard.
 */
internal object SBoardSelection {
    private val selected = WeakHashMap<Any, Boolean>()
    private val ranFirst = WeakHashMap<Any, MutableList<String>>()

    /** From "Build SBoard" and its resource half: this run builds SBoard. */
    fun select(context: Any) {
        val earlier = ranFirst[context]
        if (!earlier.isNullOrEmpty()) throw PatchException(
            "Build SBoard has to run before ${earlier.distinct().joinToString()}, which ran first. " +
                "Turn those off and patch again.",
        )
        selected[context] = true
    }

    /** For a patch SBoard does its own way: true when this run builds SBoard, so the patch leaves it alone. */
    fun builds(context: Any, patch: String): Boolean {
        if (selected.containsKey(context)) return true
        ranFirst.getOrPut(context) { mutableListOf() } += patch
        return false
    }
}
