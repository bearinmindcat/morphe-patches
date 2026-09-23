package org.ungoogled.patches.shared

import app.morphe.patcher.patch.PatchException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Just enough PNG to edit the app's own images while patching.
 *
 * Deliberately not java.awt / javax.imageio: Morphe Manager runs patches ON
 * THE PHONE, where neither exists, so anything that touches pixels has to work
 * with java.util.zip alone. Covers what Android's build tools emit: 8-bit
 * greyscale, RGB, palette, grey+alpha and RGBA, non-interlaced. Anything else
 * fails loudly rather than producing a wrong image.
 */
class Png(val width: Int, val height: Int, val argb: IntArray) {

    /** Grey + alpha (colour type 4), the format of Maps' tinted mask PNGs. */
    fun encodeGreyAlpha(): ByteArray = encode(4, 2) { x, y, out, o ->
        val c = argb[y * width + x]
        out[o] = ((c shr 16) and 0xff).toByte()
        out[o + 1] = (c ushr 24).toByte()
    }

    fun encodeRgba(): ByteArray = encode(6, 4) { x, y, out, o ->
        val c = argb[y * width + x]
        out[o] = ((c shr 16) and 0xff).toByte()
        out[o + 1] = ((c shr 8) and 0xff).toByte()
        out[o + 2] = (c and 0xff).toByte()
        out[o + 3] = (c ushr 24).toByte()
    }

    private inline fun encode(colourType: Int, bpp: Int, pixel: (Int, Int, ByteArray, Int) -> Unit): ByteArray {
        val stride = width * bpp
        val raw = ByteArray(height * (stride + 1))
        var prev = ByteArray(stride)
        val cur = ByteArray(stride)
        val trial = ByteArray(stride)
        for (y in 0 until height) {
            for (x in 0 until width) pixel(x, y, cur, x * bpp)
            // Per-row filter chosen by the usual minimum-sum-of-absolute-values heuristic.
            var bestFilter = 0
            var bestSum = Long.MAX_VALUE
            val best = ByteArray(stride)
            for (f in 0..4) {
                applyFilter(f, cur, prev, bpp, trial)
                var sum = 0L
                for (b in trial) sum += kotlin.math.abs(b.toInt())
                if (sum < bestSum) { bestSum = sum; bestFilter = f; trial.copyInto(best) }
            }
            val row = y * (stride + 1)
            raw[row] = bestFilter.toByte()
            best.copyInto(raw, row + 1)
            prev = cur.copyOf()
        }
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.write(SIGNATURE)
        chunk(out, "IHDR", ByteArrayOutputStream().also { h ->
            DataOutputStream(h).apply { writeInt(width); writeInt(height); write(8); write(colourType); write(0); write(0); write(0) }
        }.toByteArray())
        chunk(out, "IDAT", deflate(raw))
        chunk(out, "IEND", ByteArray(0))
        return bytes.toByteArray()
    }

    companion object {
        private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun decode(data: ByteArray): Png {
            if (data.size < 8 || !data.copyOfRange(0, 8).contentEquals(SIGNATURE)) throw PatchException("not a PNG")
            var pos = 8
            var width = 0; var height = 0; var depth = 0; var type = -1; var interlace = 0
            var palette = IntArray(0)
            var trns: ByteArray? = null
            val idat = ByteArrayOutputStream()
            while (pos + 8 <= data.size) {
                val len = int32(data, pos)
                val name = String(data, pos + 4, 4, Charsets.US_ASCII)
                val body = pos + 8
                when (name) {
                    "IHDR" -> {
                        width = int32(data, body); height = int32(data, body + 4)
                        depth = data[body + 8].toInt() and 0xff; type = data[body + 9].toInt() and 0xff
                        interlace = data[body + 12].toInt() and 0xff
                    }
                    "PLTE" -> palette = IntArray(len / 3) { i ->
                        val o = body + i * 3
                        (0xff shl 24) or ((data[o].toInt() and 0xff) shl 16) or ((data[o + 1].toInt() and 0xff) shl 8) or (data[o + 2].toInt() and 0xff)
                    }
                    "tRNS" -> trns = data.copyOfRange(body, body + len)
                    "IDAT" -> idat.write(data, body, len)
                    "IEND" -> break
                }
                pos = body + len + 4
            }
            if (depth != 8 || interlace != 0) throw PatchException("unsupported PNG: bit depth $depth, interlace $interlace")
            val bpp = when (type) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> throw PatchException("unsupported PNG colour type $type") }
            val stride = width * bpp
            val raw = inflate(idat.toByteArray(), height * (stride + 1))
            val argb = IntArray(width * height)
            var prev = ByteArray(stride)
            val cur = ByteArray(stride)
            for (y in 0 until height) {
                val row = y * (stride + 1)
                unfilter(raw[row].toInt() and 0xff, raw, row + 1, prev, bpp, cur)
                for (x in 0 until width) {
                    val o = x * bpp
                    fun b(i: Int) = cur[o + i].toInt() and 0xff
                    argb[y * width + x] = when (type) {
                        0 -> (0xff shl 24) or (b(0) * 0x010101)
                        2 -> (0xff shl 24) or (b(0) shl 16) or (b(1) shl 8) or b(2)
                        3 -> {
                            val i = b(0)
                            val a = trns?.let { if (i < it.size) it[i].toInt() and 0xff else 0xff } ?: 0xff
                            (a shl 24) or (palette[i] and 0xffffff)
                        }
                        4 -> (b(1) shl 24) or (b(0) * 0x010101)
                        else -> (b(3) shl 24) or (b(0) shl 16) or (b(1) shl 8) or b(2)
                    }
                }
                prev = cur.copyOf()
            }
            return Png(width, height, argb)
        }

        private fun int32(d: ByteArray, o: Int) =
            ((d[o].toInt() and 0xff) shl 24) or ((d[o + 1].toInt() and 0xff) shl 16) or ((d[o + 2].toInt() and 0xff) shl 8) or (d[o + 3].toInt() and 0xff)

        private fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }

        private fun unfilter(filter: Int, raw: ByteArray, off: Int, prev: ByteArray, bpp: Int, out: ByteArray) {
            for (i in out.indices) {
                val x = raw[off + i].toInt() and 0xff
                val a = if (i >= bpp) out[i - bpp].toInt() and 0xff else 0
                val b = prev[i].toInt() and 0xff
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xff else 0
                out[i] = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) ushr 1)
                    4 -> x + paeth(a, b, c)
                    else -> throw PatchException("bad PNG filter $filter")
                }.toByte()
            }
        }

        private fun applyFilter(filter: Int, cur: ByteArray, prev: ByteArray, bpp: Int, out: ByteArray) {
            for (i in cur.indices) {
                val x = cur[i].toInt() and 0xff
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xff else 0
                val b = prev[i].toInt() and 0xff
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xff else 0
                out[i] = when (filter) {
                    0 -> x
                    1 -> x - a
                    2 -> x - b
                    3 -> x - ((a + b) ushr 1)
                    else -> x - paeth(a, b, c)
                }.toByte()
            }
        }

        private fun inflate(data: ByteArray, expected: Int): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected && !inflater.finished()) {
                val k = inflater.inflate(out, n, expected - n)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
            inflater.end()
            if (n != expected) throw PatchException("PNG image data is truncated ($n of $expected bytes)")
            return out
        }

        private fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
            return out.toByteArray()
        }

        private fun chunk(out: DataOutputStream, name: String, body: ByteArray) {
            val crc = CRC32()
            val type = name.toByteArray(Charsets.US_ASCII)
            crc.update(type); crc.update(body)
            out.writeInt(body.size); out.write(type); out.write(body); out.writeInt(crc.value.toInt())
        }
    }
}
