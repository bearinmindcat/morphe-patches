package org.ungoogled.patches.samsung

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Samsung apps ship many icons as SPR files ("SPR" magic, often named .bmp): Samsung's own vector format, which only
 * One UI's framework can draw. Elsewhere every such drawable fails to load (Resources$NotFoundException, "<bitmap>
 * requires a valid 'src' attribute") and takes whatever inflates it down with it. This converts each one into an
 * Android vector drawable when patching: paths, circles, ellipses, rectangles and lines with their fill, stroke and
 * transforms. Gradients become their first colour; shadows and animations are dropped.
 */

private const val SPR_TAG = 0x53505200 // "SPR\0"
private const val SVF_TAG = 0x53564600 // "SVF\0"

/** Affine transform: x' = a*x + c*y + e, y' = b*x + d*y + f. */
private data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    fun x(x: Double, y: Double) = a * x + c * y + e
    fun y(x: Double, y: Double) = b * x + d * y + f
    fun then(o: Affine) = Affine( // o after this
        o.a * a + o.c * b, o.b * a + o.d * b,
        o.a * c + o.c * d, o.b * c + o.d * d,
        o.a * e + o.c * f + o.e, o.b * e + o.d * f + o.f,
    )

    val scale get() = sqrt(abs(a * d - b * c))

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }
}

private sealed class Paint {
    object None : Paint()
    object Inherit : Paint()
    data class Solid(val argb: Int) : Paint()
}

private sealed class Attr {
    data class Fill(val paint: Paint) : Attr()
    data class Stroke(val paint: Paint) : Attr()
    data class Width(val width: Float) : Attr()
    data class Cap(val cap: Int) : Attr()
    data class Join(val join: Int) : Attr()
    data class Miter(val limit: Float) : Attr()
    data class Matrix(val m: Affine) : Attr()
    data class ClipRect(val l: Float, val t: Float, val r: Float, val b: Float) : Attr()
    data class ClipPath(val link: Int) : Attr()
}

private sealed class Shape(val attrs: List<Attr>) {
    class Path(val commands: List<Pair<Int, FloatArray>>, attrs: List<Attr>) : Shape(attrs)
    class Circle(val cx: Float, val cy: Float, val r: Float, attrs: List<Attr>) : Shape(attrs)
    class Ellipse(val l: Float, val t: Float, val r: Float, val b: Float, attrs: List<Attr>) : Shape(attrs)
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, attrs: List<Attr>) : Shape(attrs)
    class Rect(val l: Float, val t: Float, val r: Float, val b: Float, val rx: Float, val ry: Float, attrs: List<Attr>) : Shape(attrs)
    class Group(val children: List<Shape>, attrs: List<Attr>) : Shape(attrs)
    class Use(val link: Int, attrs: List<Attr>) : Shape(attrs)
}

private class SprDocument(
    val left: Float, val top: Float, val right: Float, val bottom: Float, val density: Float,
    val root: Shape.Group, val references: Map<Int, Shape>,
)

private class SprReader(bytes: ByteArray) {
    private val buffer = ByteBuffer.wrap(bytes) // big endian, like DataInputStream
    var minor = 0
    val sized get() = minor >= 0x3032 // records carry their size from version "0002" on

    var position: Int
        get() = buffer.position()
        set(value) {
            buffer.position(value)
        }

    fun int() = buffer.int
    fun short() = buffer.short
    fun float() = buffer.float
    fun byte() = buffer.get()

    private fun paint(): Paint = when (val type = byte().toInt()) {
        0 -> Paint.None.also { int() }
        1 -> Paint.Solid(int())
        2 -> Paint.Inherit.also { int() }
        3, 4 -> {
            repeat(if (type == 3) 4 else 3) { float() }
            byte() // spread mode
            val stops = int()
            var first: Int? = null
            repeat(stops) {
                float()
                val rgb = int()
                val alpha = (float() * 255f).toInt().coerceIn(0, 255)
                if (first == null) first = (rgb and 0xFFFFFF) or (alpha shl 24)
            }
            byte()
            repeat(6) { float() }
            first?.let { Paint.Solid(it) } ?: Paint.None
        }
        else -> throw PatchException("unknown SPR paint type $type")
    }

    fun attributes(): List<Attr> {
        val count = int()
        val out = mutableListOf<Attr>()
        repeat(count) {
            val type = byte().toInt()
            val size = if (sized) int() else 0
            val start = position
            when (type) {
                0 -> {}
                1 -> out += Attr.ClipRect(float(), float(), float(), float())
                3 -> out += Attr.ClipPath(int())
                32 -> out += Attr.Fill(paint())
                35 -> out += Attr.Stroke(paint())
                37 -> out += Attr.Cap(byte().toInt())
                38 -> out += Attr.Join(byte().toInt())
                40 -> out += Attr.Width(float())
                41 -> out += Attr.Miter(float())
                64 -> {
                    val v = FloatArray(6) { float() } // Matrix.setValues order: scaleX skewX transX skewY scaleY transY
                    out += Attr.Matrix(Affine(v[0].toDouble(), v[3].toDouble(), v[1].toDouble(), v[4].toDouble(), v[2].toDouble(), v[5].toDouble()))
                }
                else -> if (sized) position = start + size else throw PatchException("unknown SPR attribute $type")
            }
            if (sized && type != 0) position = start + size
        }
        return out
    }

    fun shape(type: Int): Shape = when (type) {
        1 -> { val (x, y, r) = floats(3); Shape.Circle(x, y, r, attributes()) }
        2 -> { val v = floats(4); Shape.Ellipse(v[0], v[1], v[2], v[3], attributes()) }
        3 -> { val v = floats(4); Shape.Line(v[0], v[1], v[2], v[3], attributes()) }
        4 -> {
            val count = int()
            val commands = List(count) {
                val command = byte().toInt()
                val arguments = when (command) {
                    1, 2 -> 2
                    3 -> 4
                    4, 5 -> 6
                    6 -> 0
                    else -> throw PatchException("unknown SPR path command $command")
                }
                command to FloatArray(arguments) { float() }
            }
            Shape.Path(commands, attributes())
        }
        5 -> { val v = floats(6); Shape.Rect(v[0], v[1], v[2], v[3], v[4], v[5], attributes()) }
        16 -> { val children = children(); Shape.Group(children, attributes()) }
        17 -> { val link = int(); Shape.Use(link, attributes()) }
        else -> throw PatchException("unknown SPR element $type")
    }

    private fun floats(n: Int) = FloatArray(n) { float() }

    fun children(): List<Shape> {
        val count = int()
        val out = mutableListOf<Shape>()
        repeat(count) {
            val type = byte().toInt()
            val size = if (sized) int() else 0
            val start = position
            if (type in setOf(1, 2, 3, 4, 5, 16, 17)) out += shape(type) else if (sized) position = start + size
            if (sized && position != start + size) throw PatchException("SPR element $type has the wrong size")
        }
        return out
    }
}

private fun parseSpr(bytes: ByteArray): SprDocument {
    val r = SprReader(bytes)
    val tag = r.int()
    if (tag != SPR_TAG && tag != SVF_TAG) throw PatchException("not an SPR file")
    r.short()
    r.minor = r.short().toInt()
    val referencesAt = r.int()
    val documentsAt = r.int()
    r.int() // file attributes (nine-patch), not drawn here
    r.int()
    r.int()
    val left = r.float()
    val top = r.float()
    val right = r.float()
    val bottom = r.float()
    repeat(8) { r.float() } // nine-patch insets, padding
    val density = r.float()
    if (r.minor >= 0x3033) {
        r.int() // documents (animation frames): the first is the still picture
        r.int()
        r.byte()
    }
    if (r.minor >= 0x3034) {
        r.int()
        r.int()
    }
    r.position = referencesAt
    val references = mutableMapOf<Int, Shape>()
    repeat(r.int()) { index ->
        r.int()
        val type = r.byte().toInt()
        val size = if (r.sized) r.int() else 0
        val start = r.position
        if (type in setOf(1, 2, 3, 4, 5, 16, 17)) references[index] = r.shape(type) else if (r.sized) r.position = start + size
    }
    r.position = documentsAt
    val root = Shape.Group(r.children(), emptyList())
    return SprDocument(left, top, right, bottom, if (density > 0f) density else 3f, root, references)
}

private fun Double.fmt(): String {
    val rounded = Math.round(this * 1000.0) / 1000.0
    if (rounded == Math.rint(rounded) && abs(rounded) < 1e9) return rounded.toLong().toString()
    return rounded.toBigDecimal().stripTrailingZeros().toPlainString()
}

private fun Float.fmt() = toDouble().fmt()

private class PathBuilder(private val m: Affine) {
    val out = StringBuilder()
    private var cx = 0.0
    private var cy = 0.0

    private fun point(x: Double, y: Double) = "${m.x(x, y).fmt()},${m.y(x, y).fmt()}"

    fun move(x: Double, y: Double) { out.append('M').append(point(x, y)).append(' '); cx = x; cy = y }
    fun line(x: Double, y: Double) { out.append('L').append(point(x, y)).append(' '); cx = x; cy = y }
    fun quad(x1: Double, y1: Double, x: Double, y: Double) {
        out.append('Q').append(point(x1, y1)).append(' ').append(point(x, y)).append(' '); cx = x; cy = y
    }
    fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
        out.append('C').append(point(x1, y1)).append(' ').append(point(x2, y2)).append(' ').append(point(x, y)).append(' ')
        cx = x; cy = y
    }
    fun close() { out.append("Z ") }

    /** Path.arcTo(oval, start, sweep) without forceMoveTo: a line to the arc's start, then the arc as cubics. */
    fun arc(l: Double, t: Double, r: Double, b: Double, startDegrees: Double, sweepDegrees: Double, moveFirst: Boolean) {
        val rx = (r - l) / 2
        val ry = (b - t) / 2
        val ox = l + rx
        val oy = t + ry
        var angle = Math.toRadians(startDegrees)
        val sweep = Math.toRadians(sweepDegrees)
        val sx = ox + rx * cos(angle)
        val sy = oy + ry * sin(angle)
        if (moveFirst) move(sx, sy) else line(sx, sy)
        val pieces = ceil(abs(sweep) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val step = sweep / pieces
        val k = 4.0 / 3.0 * Math.tan(step / 4)
        repeat(pieces) {
            val a1 = angle + step
            val c1x = ox + rx * (cos(angle) - k * sin(angle))
            val c1y = oy + ry * (sin(angle) + k * cos(angle))
            val c2x = ox + rx * (cos(a1) + k * sin(a1))
            val c2y = oy + ry * (sin(a1) - k * cos(a1))
            cubic(c1x, c1y, c2x, c2y, ox + rx * cos(a1), oy + ry * sin(a1))
            angle = a1
        }
    }

    fun ellipse(l: Double, t: Double, r: Double, b: Double) {
        arc(l, t, r, b, 0.0, 360.0, true)
        close()
    }

    fun roundRect(l: Double, t: Double, r: Double, b: Double, rxIn: Double, ryIn: Double) {
        val rx = rxIn.coerceIn(0.0, (r - l) / 2)
        val ry = ryIn.coerceIn(0.0, (b - t) / 2)
        if (rx <= 0 || ry <= 0) {
            move(l, t); line(r, t); line(r, b); line(l, b); close()
            return
        }
        move(l + rx, t)
        line(r - rx, t)
        arc(r - 2 * rx, t, r, t + 2 * ry, 270.0, 90.0, false)
        line(r, b - ry)
        arc(r - 2 * rx, b - 2 * ry, r, b, 0.0, 90.0, false)
        line(l + rx, b)
        arc(l, b - 2 * ry, l + 2 * rx, b, 90.0, 90.0, false)
        line(l, t + ry)
        arc(l, t, l + 2 * rx, t + 2 * ry, 180.0, 90.0, false)
        close()
    }

    fun commands(commands: List<Pair<Int, FloatArray>>) {
        var started = false
        for ((command, v) in commands) {
            val d = DoubleArray(v.size) { v[it].toDouble() }
            when (command) {
                1 -> { move(d[0], d[1]); started = true }
                2 -> { if (started) line(d[0], d[1]) else move(d[0], d[1]); started = true }
                3 -> { if (!started) move(cx, cy); quad(d[0], d[1], d[2], d[3]); started = true }
                4 -> { if (!started) move(cx, cy); cubic(d[0], d[1], d[2], d[3], d[4], d[5]); started = true }
                5 -> { arc(d[0], d[1], d[0] + d[2], d[1] + d[3], d[4], d[5], !started); started = true }
                6 -> close()
            }
        }
    }
}

private data class Style(
    val fill: Paint = Paint.None,
    val stroke: Paint = Paint.None,
    val width: Float = 1f,
    val cap: Int = 1,
    val join: Int = 1,
    val miter: Float = 4f,
)

private class VectorWriter(private val doc: SprDocument) {
    val body = StringBuilder()
    private var depth = 1

    private fun color(argb: Int) = "#%08X".format(argb)

    private fun indent() = "    ".repeat(depth)

    private fun outline(shape: Shape, m: Affine): String? {
        val p = PathBuilder(m)
        when (shape) {
            is Shape.Path -> p.commands(shape.commands)
            is Shape.Circle -> p.ellipse((shape.cx - shape.r).toDouble(), (shape.cy - shape.r).toDouble(), (shape.cx + shape.r).toDouble(), (shape.cy + shape.r).toDouble())
            is Shape.Ellipse -> p.ellipse(shape.l.toDouble(), shape.t.toDouble(), shape.r.toDouble(), shape.b.toDouble())
            is Shape.Rect -> p.roundRect(shape.l.toDouble(), shape.t.toDouble(), shape.r.toDouble(), shape.b.toDouble(), shape.rx.toDouble(), shape.ry.toDouble())
            is Shape.Line -> { p.move(shape.x1.toDouble(), shape.y1.toDouble()); p.line(shape.x2.toDouble(), shape.y2.toDouble()) }
            else -> return null
        }
        return p.out.toString().trim().ifEmpty { null }
    }

    fun write(shape: Shape, parent: Style, parentMatrix: Affine) {
        var style = parent
        var m = parentMatrix
        val clips = mutableListOf<String>()
        for (attr in shape.attrs) {
            style = when (attr) {
                is Attr.Fill -> if (attr.paint == Paint.Inherit) style else style.copy(fill = attr.paint)
                is Attr.Stroke -> if (attr.paint == Paint.Inherit) style else style.copy(stroke = attr.paint)
                is Attr.Width -> style.copy(width = attr.width)
                is Attr.Cap -> style.copy(cap = attr.cap)
                is Attr.Join -> style.copy(join = attr.join)
                is Attr.Miter -> style.copy(miter = attr.limit)
                else -> style
            }
            when (attr) {
                is Attr.Matrix -> m = attr.m.then(m)
                is Attr.ClipRect -> outline(Shape.Rect(attr.l, attr.t, attr.r, attr.b, 0f, 0f, emptyList()), m)?.let { clips += it }
                is Attr.ClipPath -> doc.references[attr.link]?.let { outline(it, m) }?.let { clips += it }
                else -> {}
            }
        }
        if (clips.isNotEmpty()) {
            body.append(indent()).append("<group>\n")
            depth++
            for (clip in clips) body.append(indent()).append("<clip-path android:pathData=\"").append(clip).append("\"/>\n")
        }
        when (shape) {
            is Shape.Group -> shape.children.forEach { write(it, style, m) }
            is Shape.Use -> doc.references[shape.link]?.let { write(it, style, m) }
            else -> {
                val data = outline(shape, m)
                val fill = style.fill as? Paint.Solid
                val stroke = style.stroke as? Paint.Solid
                if (data != null && (fill != null || stroke != null)) {
                    body.append(indent()).append("<path android:pathData=\"").append(data).append('"')
                    if (fill != null && shape !is Shape.Line) body.append(" android:fillColor=\"").append(color(fill.argb)).append('"')
                    if (stroke != null) {
                        val width = (if (style.width > 0f) style.width.toDouble() else 1.0 / doc.density) * m.scale
                        body.append(" android:strokeColor=\"").append(color(stroke.argb)).append('"')
                            .append(" android:strokeWidth=\"").append(width.fmt()).append('"')
                            .append(" android:strokeLineCap=\"").append(arrayOf("butt", "round", "square").getOrElse(style.cap - 1) { "butt" }).append('"')
                            .append(" android:strokeLineJoin=\"").append(arrayOf("miter", "round", "bevel").getOrElse(style.join - 1) { "miter" }).append('"')
                            .append(" android:strokeMiterLimit=\"").append(style.miter.fmt()).append('"')
                    }
                    body.append("/>\n")
                }
            }
        }
        if (clips.isNotEmpty()) {
            depth--
            body.append(indent()).append("</group>\n")
        }
    }
}

/** The vector drawable for an SPR file, or null if [bytes] is not one. Extra root attributes go on the <vector>. */
internal fun sprToVector(bytes: ByteArray, extraAttributes: String = ""): String? {
    if (bytes.size < 4 || ByteBuffer.wrap(bytes).int.let { it != SPR_TAG && it != SVF_TAG }) return null
    val doc = parseSpr(bytes)
    val writer = VectorWriter(doc)
    writer.write(doc.root, Style(), Affine(1.0, 0.0, 0.0, 1.0, -doc.left.toDouble(), -doc.top.toDouble()))
    val w = (doc.right - doc.left).toDouble()
    val h = (doc.bottom - doc.top).toDouble()
    return buildString {
        append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"")
        append(" android:width=\"").append((w / doc.density).fmt()).append("dp\"")
        append(" android:height=\"").append((h / doc.density).fmt()).append("dp\"")
        append(" android:viewportWidth=\"").append(w.fmt()).append('"')
        append(" android:viewportHeight=\"").append(h.fmt()).append('"')
        append(extraAttributes)
        append(">\n")
        append(writer.body)
        append("</vector>")
    }
}

private val BITMAP = Regex("""<bitmap\b([^>]*?)/>""", RegexOption.DOT_MATCHES_ALL)
private val ATTRIBUTE = Regex("""([\w:]+)\s*=\s*"([^"]*)"""")

/**
 * Converts every SPR drawable under res/ into a vector drawable (same name, .xml), and replaces each <bitmap> that
 * shows one -- which needs a real bitmap -- with the vector itself, keeping its tint, tint mode, alpha and mirroring.
 * Returns the drawables converted.
 */
internal fun ResourcePatchContext.convertSprDrawables(): Int {
    // Resources are decoded on request: list them, then decode only the drawables.
    val drawables = listDecodableFiles("res/**").filter { path ->
        val dir = path.split('/').getOrNull(1) ?: return@filter false
        path.count { it == '/' } == 2 && (dir == "drawable" || dir.startsWith("drawable-") || dir.startsWith("mipmap"))
    }
    val vectors = mutableMapOf<String, String>() // drawable name -> vector XML (any configuration)
    var converted = 0
    for (path in drawables.filter { !it.endsWith(".xml") }) {
        val file = get(path)
        if (!file.isFile || file.length() < 4) continue
        val head = ByteArray(4)
        file.inputStream().use { it.read(head) }
        if (ByteBuffer.wrap(head).int.let { it != SPR_TAG && it != SVF_TAG }) continue
        val vector = sprToVector(file.readBytes()) ?: continue
        val name = path.substringAfterLast('/').substringBefore('.')
        val dir = path.substringBeforeLast('/')
        delete(path)
        get("$dir/$name.xml").writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$vector\n")
        vectors.putIfAbsent(name, vector)
        converted++
    }
    if (vectors.isEmpty()) return 0
    for (path in drawables.filter { it.endsWith(".xml") }) {
        val file = get(path)
        if (!file.isFile) continue
        val text = file.readText()
        if (!text.contains("<bitmap")) continue
        val changed = BITMAP.replace(text) { match ->
            val attributes = ATTRIBUTE.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val src = attributes["android:src"]?.removePrefix("@drawable/") ?: return@replace match.value
            val vector = vectors[src] ?: return@replace match.value
            val extra = buildString {
                for (key in listOf("android:tint", "android:tintMode", "android:alpha", "android:autoMirrored")) {
                    attributes[key]?.let { append(" $key=\"$it\"") }
                }
            }
            // The <vector> start tag is the first '>' (no attribute value written above holds one).
            if (extra.isEmpty()) vector else vector.replaceFirst(">", "$extra>")
        }
        if (changed != text) file.writeText(changed)
    }
    return converted
}

// The simpler replacement Video Player and Photo Editor use: plain icons, not converted artwork.

private val SPR_MAGIC = byteArrayOf('S'.code.toByte(), 'P'.code.toByte(), 'R'.code.toByte(), 0)

/** Plain icons (24 x 24) for the media controls Samsung ships as SPR, by the last word of the drawable's name. */
private val ICONS = listOf(
    "pause" to "M6,5h4v14H6zM14,5h4v14h-4z",
    "play" to "M8,5v14l11,-7z",
    "next" to "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z",
    "prev" to "M6,6h2v12H6zM9.5,12l8.5,6V6z",
    "stop" to "M6,6h12v12H6z",
    "close" to "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
    "mute" to "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zM19,12c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z",
)

private fun vector(path: String?) = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="${if (path != null) "#FFFFFFFF" else "#00000000"}" android:pathData="${path ?: "M0,0h1v1H0z"}" />
</vector>
"""

/**
 * Off One UI a drawable in Samsung's SPR vector format cannot be decoded: inflating it fails, in the app or (for a
 * notification's views) in the system UI, which then kills the app. Every SPR drawable is replaced with a plain vector
 * drawable of the same name: a media control icon where the name says which one, otherwise an empty one. Returns the
 * names replaced, with what each became ("" = empty).
 */
internal fun ResourcePatchContext.replaceSprDrawables(): Map<String, String> {
    // Patcher 2.0 decodes resources on request.
    decode("res/drawable*/*")
    val res = this["res"]
    val replaced = sortedMapOf<String, String>()
    for (dir in res.listFiles()!!.filter { it.isDirectory && (it.name == "drawable" || it.name.startsWith("drawable-")) }) {
        for (file in dir.listFiles()!!) {
            if (!file.isFile || file.name.endsWith(".xml")) continue
            val head = file.inputStream().use { it.readNBytes(4) }
            if (!head.contentEquals(SPR_MAGIC)) continue
            val name = file.name.substringBefore('.')
            // A control's icon ("..._control_ic_play", "..._btn_mute"), not an image that merely ends in a word.
            val control = name.contains("control") || name.contains("btn")
            val icon = ICONS.firstOrNull { (key, _) -> control && name.substringAfterLast('_') == key }
            file.delete()
            File(dir, "$name.xml").writeText(vector(icon?.second))
            replaced["${dir.name}/$name"] = icon?.first ?: ""
        }
    }
    return replaced
}
