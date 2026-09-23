package org.ungoogled.patches.maps.resources

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.Png
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Chromium-coloured flat pin: the pin's own two blues (#4285F4, #1A73E8) stay as
 * they are; only the red/yellow/green segments are replaced, using the three ring
 * colours of the actual Chromium logo (#1967D2 dark, #669DF6 medium, #AECBFA
 * light), matching how ungoogled-chromium recolours Chrome.
 *
 * Everything is recoloured here, from the Maps being patched -- no artwork ships
 * with the patch, and it works for whichever density split the user's bundle has.
 * Files are found by resource name in the decoded tree, never by the obfuscated
 * archive paths, which differ between builds and splits.
 */
private val SOURCE = mapOf(
    "red" to Triple(0xEA, 0x43, 0x35), "yellow" to Triple(0xFB, 0xBC, 0x04), "green" to Triple(0x34, 0xA8, 0x53),
)
// POSITIONAL, like Chrome -> Chromium: Chrome's red is the top segment, yellow
// bottom-left, green bottom-right; Chromium paints those same positions dark /
// medium / light.
private val TARGET = mapOf(
    "red" to Triple(0x19, 0x67, 0xD2), "yellow" to Triple(0x66, 0x9D, 0xF6), "green" to Triple(0xAE, 0xCB, 0xFA),
)

/** A flat (three discrete regions) pin: every opaque pixel within reach of a source
 *  colour moves by that segment's delta, so anti-aliased edges follow. */
private fun recolourFlat(png: Png): Png {
    val tolerance = 70 * 70
    val out = png.argb.copyOf()
    for (i in out.indices) {
        val c = out[i]
        val a = c ushr 24
        if (a == 0) continue
        val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for ((name, s) in SOURCE) {
            val d = (r - s.first) * (r - s.first) + (g - s.second) * (g - s.second) + (b - s.third) * (b - s.third)
            if (d < bestDistance) { bestDistance = d; best = name }
        }
        if (bestDistance > tolerance) continue
        val s = SOURCE.getValue(best!!); val t = TARGET.getValue(best)
        val nr = (r + t.first - s.first).coerceIn(0, 255)
        val ng = (g + t.second - s.second).coerceIn(0, 255)
        val nb = (b + t.third - s.third).coerceIn(0, 255)
        out[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
    return Png(png.width, png.height, out)
}

private fun hsv(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
    val rf = r / 255.0; val gf = g / 255.0; val bf = b / 255.0
    val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
    val v = mx
    if (mn == mx) return Triple(0.0, 0.0, v)
    val s = (mx - mn) / mx
    val rc = (mx - rf) / (mx - mn); val gc = (mx - gf) / (mx - mn); val bc = (mx - bf) / (mx - mn)
    var h = when (mx) { rf -> bc - gc; gf -> 2.0 + rc - bc; else -> 4.0 + gc - rc }
    h = (h / 6.0) % 1.0
    if (h < 0) h += 1.0
    return Triple(h, s, v)
}

private fun rgb(h: Double, s: Double, v: Double): Triple<Double, Double, Double> {
    if (s == 0.0) return Triple(v, v, v)
    val i = (h * 6.0).toInt()
    val f = h * 6.0 - i
    val p = v * (1.0 - s); val q = v * (1.0 - s * f); val t = v * (1.0 - s * (1.0 - f))
    return when (i % 6) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
}

/**
 * The 2025 pin is a smooth GRADIENT, not three regions: the discrete match leaves
 * visible magenta/green seams wherever a blended pixel falls outside every
 * anchor's tolerance. Instead each pixel's hue is weighted across the same three
 * anchors (inverse square hue distance), giving a continuous blend of the three
 * target saturations/values at Chromium's hue, with the pixel's own brightness
 * kept as shading.
 */
private fun recolourGradient(png: Png): Png {
    val anchors = SOURCE.keys.map { k ->
        val s = SOURCE.getValue(k); val t = TARGET.getValue(k)
        val sh = hsv(s.first, s.second, s.third).first * 360
        val th = hsv(t.first, t.second, t.third)
        Triple(sh, th.second, th.third)
    }
    fun hueDistance(a: Double, b: Double): Double { val d = abs(a - b) % 360; return min(d, 360 - d) }
    val out = png.argb.copyOf()
    for (i in out.indices) {
        val c = out[i]
        val a = c ushr 24
        if (a == 0) continue
        val (h, s, v) = hsv((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff)
        if (s < 0.12) { out[i] = (a shl 24) or 0xE8F0FE; continue }
        val hue = h * 360
        val weights = anchors.map { 1.0 / (hueDistance(hue, it.first).let { d -> d * d } + 1.0) }
        val total = weights.sum()
        val sat = weights.zip(anchors).sumOf { (w, an) -> w * an.second } / total
        var value = weights.zip(anchors).sumOf { (w, an) -> w * an.third } / total
        value = (value * (0.55 + 0.45 * v)).coerceIn(0.0, 1.0)
        val (nr, ng, nb) = rgb(216.0 / 360.0, sat, value)
        out[i] = (a shl 24) or ((nr * 255).toInt() shl 16) or ((ng * 255).toInt() shl 8) or (nb * 255).toInt()
    }
    return Png(png.width, png.height, out)
}

/** The search bar's leading icon (Maps draws it with its own SVG renderer) and the Android Auto pin. */
private val SVG_PINS = listOf("ic_maps_pin_icon_2020.svg", "car_ic_logo_pin.svg")
private val SVG_COLOURS = mapOf("#EA4335" to "#1967D2", "#FBBC04" to "#669DF6", "#FBBC05" to "#669DF6", "#34A853" to "#AECBFA")

@Suppress("unused")
val bluePinPatch = resourcePatch(
    name = "Blue pin",
    description = "Chromium-coloured flat map pin on every in-app product logo and the search bar's leading icon.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_MAPS)

    execute {
        val res = this["res"]
        var flat = 0; var gradient = 0
        for (dir in res.listFiles()!!.filter { it.isDirectory && (it.name == "drawable" || it.name.startsWith("drawable-")) }) {
            for (file in dir.listFiles()!!.filter { it.name.startsWith("product_logo_maps") && it.name.endsWith(".png") }) {
                val png = Png.decode(file.readBytes())
                val recoloured = if (file.name.startsWith("product_logo_maps_2025")) { gradient++; recolourGradient(png) } else { flat++; recolourFlat(png) }
                file.writeBytes(recoloured.encodeRgba())
            }
        }
        if (flat == 0 || gradient == 0) throw PatchException("product logo PNGs not found ($flat flat, $gradient gradient)")

        var svgs = 0
        for (name in SVG_PINS) {
            val file = File(res, "raw/$name").takeIf { it.isFile } ?: throw PatchException("raw/$name not found")
            var text = file.readText()
            val before = text
            for ((from, to) in SVG_COLOURS) text = text.replace(from, to, ignoreCase = true)
            if (text == before) throw PatchException("raw/$name has none of the pin's red/yellow/green left to recolour")
            file.writeText(text)
            svgs++
        }
    }
}
