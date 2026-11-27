package org.ungoogled.patches.shared

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchBuilder
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.stringOption

/** The option on a microG app's "Add microG-RE support" patch that picks the package it installs under. */
internal const val MICROG_PACKAGE_NAME = "packageName"

private val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

/**
 * Adds the [MICROG_PACKAGE_NAME] option for [app] (Gmail, Photos), [default] being the patch's own package. [stock] is
 * the app's own package when the patch can also install under it, on phones without the built-in app (issue #249).
 */
internal fun PatchBuilder<*>.microgPackageNameOption(app: String, default: String, stock: String? = null) = stringOption(
    key = MICROG_PACKAGE_NAME,
    default = default,
    title = "Package name",
    description = if (stock == null) {
        "The package microG $app installs under. Must be a valid Android package name."
    } else {
        "The package microG $app installs under. $stock only on phones without $app."
    },
    required = true,
) { it != null && it.matches(PACKAGE_NAME) }

/**
 * The package [patch] (a microG app's "Add microG-RE support") installs under: its option, or [default]. The stock
 * package [stock] would collide with the built-in app, so it is refused unless [stockAllowed] (no built-in app).
 */
internal fun microgPackageName(patch: Patch<*>, default: String, stock: String, stockAllowed: Boolean = false): String {
    val chosen = (patch.options[MICROG_PACKAGE_NAME]?.value as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: default
    if (!chosen.matches(PACKAGE_NAME)) throw PatchException("\"$chosen\" is not a valid package name")
    if (chosen == stock && !stockAllowed) throw PatchException("the package name must differ from $stock")
    return chosen
}
