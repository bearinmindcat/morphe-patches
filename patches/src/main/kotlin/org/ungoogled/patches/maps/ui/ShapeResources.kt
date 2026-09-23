package org.ungoogled.patches.maps.ui

import org.ungoogled.patches.shared.Png
import java.io.File
import java.math.BigDecimal
import java.math.MathContext

/**
 * Resource halves of Rectangle shapes and Black theme: sharp-cornered and
 * blackened copies of the app's own resources, emitted as extra
 * configurations of the SAME resources so the runtime can switch between them
 * (Shapes.wrap sets mnc/mcc 9999 on each Activity's override Configuration).
 *
 * 9999 because Morphe reserves mobile codes 1000..9999 for configurations
 * that patches add, and encodes those values directories itself. mcc and mnc
 * are independent, so the two features combine freely; where one drawable
 * needs both, a combined -mcc9999-mnc9999 copy is written, because mcc
 * outranks mnc and a colour-only copy would otherwise lose the corners.
 */
internal object ShapeResources {
    const val RECT = "mnc9999"
    const val BLACK = "mcc9999"
    private const val RADIUS_DP = 8.0
    private const val NEW = "8.0dp"

    /** Directories this patch set generated itself, never used as a source. */
    fun isGenerated(dir: File) = RECT in dir.name || BLACK in dir.name

    /** `values` -> `values-mnc9999`, `drawable-night-v31` -> `drawable-mnc9999-night-v31`.
     *  Carrier qualifiers come first, mcc before mnc, per Android's qualifier order. */
    fun qualified(dirName: String, vararg add: String): String {
        val parts = dirName.split('-')
        val rest = parts.drop(1).toMutableList()
        val carrier = rest.takeWhile { it.startsWith("mcc") || it.startsWith("mnc") }.toMutableList()
        repeat(carrier.size) { rest.removeAt(0) }
        carrier += add
        carrier.sortBy { if (it.startsWith("mcc")) 0 else 1 }
        return (listOf(parts.first()) + carrier + rest).joinToString("-")
    }

    fun writeValues(file: File, items: List<String>) {
        file.parentFile.mkdirs()
        file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n" + items.joinToString("\n") + "\n</resources>\n")
    }

    // ---- Rectangle shapes -----------------------------------------------------

    private val cornerDimen = Regex(
        "corner|rounded|(?=.*radius)(?=.*(card|chip|button|sheet|pill|box|background|container|bubble|dialog|edge|border))",
        RegexOption.IGNORE_CASE,
    )
    private val cornerStyleItem = Regex("name=\"(?:[a-zA-Z]+:)?(cornerSize[A-Za-z]*|cornerRadius|shapeCornerSize[A-Za-z]*)\"")
    private val cornerStyleValue = Regex(
        "(<item name=\"(?:[a-zA-Z]+:)?(?:cornerSize[A-Za-z]*|cornerRadius|shapeCornerSize[A-Za-z]*)\">)([^<]*)(</item>)",
    )
    private val dimension = Regex("^(-?\\d+(?:\\.\\d+)?)(dp|dip|px|sp)$")
    private val percent = Regex("^\\d+(?:\\.\\d+)?%$")

    /** '28.0dp' -> 8.0dp; '50.0%' -> 8.0dp; '0.0dp' stays square; references are left alone. */
    fun clamp(value: String): String {
        val v = value.trim()
        dimension.matchEntire(v)?.let { return if (it.groupValues[1].toDouble() == 0.0) v else NEW }
        if (percent.matches(v)) return NEW
        return v
    }

    /** Corner-ish dimens of one values/dimens.xml, clamped; empty if none change. */
    fun rectDimens(xml: String): List<String> =
        Regex("<dimen name=\"([^\"]+)\">([^<]*)</dimen>").findAll(xml).mapNotNull { m ->
            val (name, value) = m.destructured
            if (!cornerDimen.containsMatchIn(name)) return@mapNotNull null
            val clamped = clamp(value)
            if (clamped == value) null else "    <dimen name=\"$name\">$clamped</dimen>"
        }.toList()

    /** Every style carrying a corner item, re-declared with those items clamped. */
    fun rectStyles(xml: String): List<String> =
        Regex("<style [^>]*>.*?</style>|<style [^>]*/>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val block = m.value
            if (!cornerStyleItem.containsMatchIn(block)) return@mapNotNull null
            val rewritten = cornerStyleValue.replace(block) { it.groupValues[1] + clamp(it.groupValues[2]) + it.groupValues[3] }
            "    " + rewritten.replace("\n", "\n    ")
        }.toList()

    private val radiusAttr = Regex("(android:(?:radius|topLeftRadius|topRightRadius|bottomLeftRadius|bottomRightRadius)=\")([^\"]*)(\")")

    /** A shape or pill-backed vector drawable, sharp-cornered; null when it has nothing to change. */
    fun rectDrawable(xml: String): String? {
        if ("<vector" in xml) return vectorPillToRect(xml).takeIf { it != xml }
        if ("<corners" !in xml && "android:shape=\"oval\"" !in xml) return null
        if ("\"@null\"" in xml) return null
        var out = radiusAttr.replace(xml) { it.groupValues[1] + clamp(it.groupValues[2]) + it.groupValues[3] }
        // circles (icon backgrounds, dots) -> small-radius rectangles
        if ("android:shape=\"oval\"" in out) {
            out = out.replace("android:shape=\"oval\"", "android:shape=\"rectangle\"")
            if ("<corners" !in out) {
                val corners = "<corners android:radius=\"$NEW\" />"
                val selfClosing = Regex("<shape([^>]*?)\\s*/>")
                out = if (selfClosing.containsMatchIn(out)) {
                    selfClosing.replaceFirst(out, "<shape$1>\n    ${Regex.escapeReplacement(corners)}\n</shape>")
                } else {
                    Regex("(<shape[^>]*>)").replaceFirst(out, "$1\n    ${Regex.escapeReplacement(corners)}")
                }
            }
        }
        return out.takeIf { it != xml }
    }

    /** Python's %g: six significant digits, trailing zeros dropped. */
    private fun g(x: Double): String = BigDecimal(x).round(MathContext(6)).stripTrailingZeros().toPlainString()

    /**
     * Button-sized vectors that bake their own round or pill background into path
     * data (the search button: a 54x62 viewport whose background path is arcs of
     * radius ~viewport/2). Such a path is replaced by a rounded rectangle; small
     * icon glyphs (24dp viewports) are left alone.
     */
    private fun vectorPillToRect(xml: String): String {
        val w = Regex("android:viewportWidth=\"([\\d.]+)\"").find(xml)?.groupValues?.get(1)?.toDouble() ?: return xml
        val h = Regex("android:viewportHeight=\"([\\d.]+)\"").find(xml)?.groupValues?.get(1)?.toDouble() ?: return xml
        if (w < 40 || h < 40) return xml
        val widthDp = Regex("android:width=\"([\\d.]+)dp\"").find(xml)?.groupValues?.get(1)?.toDouble()
        val r = if (widthDp != null) RADIUS_DP * w / widthDp else RADIUS_DP
        val rect = "M${g(r)},0L${g(w - r)},0A${g(r)},${g(r)} 0,0 1,${g(w)},${g(r)}" +
            "L${g(w)},${g(h - r)}A${g(r)},${g(r)} 0,0 1,${g(w - r)},${g(h)}" +
            "L${g(r)},${g(h)}A${g(r)},${g(r)} 0,0 1,0,${g(h - r)}" +
            "L0,${g(r)}A${g(r)},${g(r)} 0,0 1,${g(r)},0z"
        return Regex("(android:pathData=\")([^\"]+)(\")").replace(xml) { m ->
            val arcs = Regex("[Aa]\\s*([\\d.]+)[, ]+([\\d.]+)").findAll(m.groupValues[2]).toList()
            if (arcs.size >= 2 && arcs.all {
                    kotlin.math.abs(it.groupValues[1].toDouble() - w / 2) <= 1.0 &&
                        kotlin.math.abs(it.groupValues[2].toDouble() - w / 2) <= 1.0
                }
            ) m.groupValues[1] + rect + m.groupValues[3] else m.value
        }
    }

    /**
     * The two round navigation controls (en-route Search and route options) get
     * their round face from a PNG whose alpha channel is a filled circle, tinted at
     * runtime, so no radius anywhere reaches them. Redraw such a mask as a rounded
     * square over the original's own opaque box, keeping size, padding, grey level
     * and peak opacity.
     */
    fun squareMask(data: ByteArray): ByteArray {
        val png = Png.decode(data)
        val w = png.width
        val h = png.height
        var peak = 0
        var left = w; var top = h; var right = 0; var bottom = 0
        for (y in 0 until h) for (x in 0 until w) {
            val a = png.argb[y * w + x] ushr 24
            if (a > peak) peak = a
            if (a > 8) {
                if (x < left) left = x
                if (y < top) top = y
                if (x + 1 > right) right = x + 1
                if (y + 1 > bottom) bottom = y + 1
            }
        }
        require(peak > 0 && right > left) { "empty alpha mask" }
        val grey = (png.argb[(h / 2) * w + w / 2] shr 16) and 0xff
        val radius = maxOf(1.0, Math.round(w * RADIUS_DP / 64.0).toDouble())
        val ss = 4
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var covered = 0
            for (j in 0 until ss) for (i in 0 until ss) {
                val sx = x + (i + 0.5) / ss
                val sy = y + (j + 0.5) / ss
                if (sx < left || sx >= right || sy < top || sy >= bottom) continue
                val cx = sx.coerceIn(left + radius, right - radius)
                val cy = sy.coerceIn(top + radius, bottom - radius)
                if ((sx - cx) * (sx - cx) + (sy - cy) * (sy - cy) <= radius * radius) covered++
            }
            val alpha = Math.round(peak * covered / (ss * ss).toDouble()).toInt()
            out[y * w + x] = (alpha shl 24) or (grey * 0x010101)
        }
        return Png(w, h, out).encodeGreyAlpha()
    }

    // ---- Black theme ------------------------------------------------------------

    /**
     * Dark neutral greys -> black, alpha kept, everything else untouched. The
     * same table as Shapes.color() in the extension, which applies it again at
     * draw time, so both outputs must be fixed points: 0x00 falls in the first
     * band and the lifted container grey (#2a2a2a) is passed through as a marker.
     */
    fun remap(hex: String): String? {
        var h = hex.removePrefix("#")
        if (h.length == 3 || h.length == 4) h = h.map { "$it$it" }.joinToString("")
        if (h.length == 6) h = "ff$h"
        if (h.length != 8) return null
        val (a, r, g, b) = (0 until 4).map { h.substring(it * 2, it * 2 + 2).toInt(16) }
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx >= 0x4c || mx - mn >= 0x0c) return null   // tinted dark containers keep their tint
        if (r == 0x2a && g == 0x2a && b == 0x2a) return null
        val v = when {
            mx <= 0x06 -> 0x00
            mx <= 0x10 -> 0x2a   // the row/card container -> visible under a black ground
            else -> 0x00
        }
        return "#%02x%02x%02x%02x".format(a, v, v, v)
    }

    /** Every colour of one colors.xml, name to value (a literal or a reference). */
    fun colourEntries(xml: String): List<Pair<String, String>> =
        Regex("<color name=\"([^\"]+)\">([^<]+)</color>").findAll(xml).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

    /** Whether Black theme changes this colour value. */
    fun blackens(value: String): Boolean = remap(value)?.equals(value, ignoreCase = true) == false

    private val colourAttr = Regex("(android:(?:color|fillColor|strokeColor|startColor|centerColor|endColor)=\")(#[0-9a-fA-F]{3,8})(\")")

    /** A drawable with its dark neutral literal fills remapped; null when nothing changes. */
    fun blackDrawable(xml: String): String? {
        if ("\"@null\"" in xml) return null
        val out = colourAttr.replace(xml) { m -> m.groupValues[1] + (remap(m.groupValues[2]) ?: m.groupValues[2]) + m.groupValues[3] }
        return out.takeIf { it != xml }
    }
}
