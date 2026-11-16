package org.ungoogled.patches.shared

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.PatchBuilder
import app.morphe.patcher.patch.booleanOption
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * microG's C -- its own logo, four arcs open to the right -- as a badge on a Google app's icon (Gmail's M, Photos'
 * pinwheel), in the adaptive icon's 108-unit space: a white disc at the lower right, inside the area every launcher
 * mask keeps, with the C at 86% of it so a white ring stays around it (microG's proportions: 30 wide at radius 70).
 */
internal fun microgBadge(): String {
    val cx = 68.5
    val cy = 68.5
    val disc = 13.5
    val outer = disc * 0.86
    val width = outer * 30 / 70
    val r = outer - width / 2
    fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    fun at(degrees: Int): String {
        val a = Math.toRadians(degrees.toDouble())
        return "${f(cx + r * cos(a))},${f(cy + r * sin(a))}"
    }
    val arcs = listOf(Triple(45, 120, "#E91E63"), Triple(120, 195, "#FF9800"), Triple(195, 240, "#CDDC39"), Triple(240, 315, "#009688"))
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        append("    android:width=\"108dp\" android:height=\"108dp\"\n")
        append("    android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n")
        append("    <path android:fillColor=\"#FFFFFF\"\n")
        append("        android:pathData=\"M${f(cx - disc)},${f(cy)} a${f(disc)},${f(disc)} 0 1,0 ${f(2 * disc)},0 a${f(disc)},${f(disc)} 0 1,0 ${f(-2 * disc)},0z\" />\n")
        for ((from, to, colour) in arcs) {
            append("    <path android:strokeColor=\"$colour\" android:strokeLineCap=\"butt\" android:strokeWidth=\"${f(width)}\"\n")
            append("        android:pathData=\"M${at(from)} A${f(r)},${f(r)} 0 0,1 ${at(to)}\" />\n")
        }
        append("</vector>\n")
    }
}

/** The option on a microG app's "Add microG-RE support" patch that keeps the app's own launcher icon and name. */
internal const val KEEP_ICON_AND_NAME = "keepIconAndName"

/** Adds the [KEEP_ICON_AND_NAME] option, for [app] (Gmail, Photos). */
internal fun PatchBuilder<*>.keepIconAndNameOption(app: String) = booleanOption(
    key = KEEP_ICON_AND_NAME,
    default = false,
    title = "Keep $app's icon and name",
    description = "No microG badge on the icon and no \"microG\" in the name. Still a separate app.",
)

/** True when [patch] (a microG app's "Add microG-RE support") was asked to keep the app's own icon and name. */
internal fun keepsIconAndName(patch: Patch<*>): Boolean = patch.options[KEEP_ICON_AND_NAME]?.value == true
