package org.ungoogled.samsunggallery.ocr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finds and reads the text in a picture with PP-OCRv5 (mobile): the detection network marks text pixels, the marked
 * areas become text lines (the DB method PaddleOCR uses: threshold, smallest rotated rectangle, grown by a margin),
 * each line is cut out straight and 48 pixels high, and the recognition network reads it (CTC). Where in the line each
 * character was read gives its box.
 *
 * Quads are 8 floats, corners in reading order: top left, top right, bottom right, bottom left of the text as read.
 */
final class TextReader {
    /** A character (or, as " ", the gap between two words), with its quad in the picture. */
    static final class Glyph {
        final String text;
        final float[] quad;

        Glyph(String text, float[] quad) {
            this.text = text;
            this.quad = quad;
        }
    }

    static final class Line {
        final float[] quad;
        final String text;
        final float score;
        final List<Glyph> glyphs;

        Line(float[] quad, String text, float score, List<Glyph> glyphs) {
            this.quad = quad;
            this.text = text;
            this.score = score;
            this.glyphs = glyphs;
        }
    }

    // PaddleOCR's defaults for DB post-processing and line recognition.
    private static final float PIXEL_THRESHOLD = 0.3f;
    private static final float BOX_THRESHOLD = 0.6f;
    private static final float UNCLIP_RATIO = 1.5f;
    private static final float MIN_SIDE = 3f;
    private static final int MAX_LINES = 1000;
    private static final float LINE_SCORE = 0.5f;
    private static final int LINE_HEIGHT = 48;
    /** Lines narrower than this are padded to it, as PaddleOCR does (48 x 320 is the recognizer's training shape). */
    private static final int MIN_LINE_WIDTH = 320;
    private static final int MAX_LINE_WIDTH = 48 * 40;

    private final long handle;
    private final String[] dictionary;

    TextReader(long handle, String[] dictionary) {
        this.handle = handle;
        this.dictionary = dictionary;
    }

    boolean hasText(Bitmap picture, int maxSide) {
        return !find(picture, maxSide).isEmpty();
    }

    List<Line> read(Bitmap picture, int maxSide) {
        Bitmap source = argb(picture);
        List<float[]> quads = find(source, maxSide);
        List<Line> lines = new ArrayList<>();
        for (float[] quad : quads) {
            Line line = readLine(source, quad);
            if (line != null) lines.add(line);
        }
        return lines;
    }

    private static Bitmap argb(Bitmap picture) {
        if (picture.getConfig() == Bitmap.Config.ARGB_8888) return picture;
        return picture.copy(Bitmap.Config.ARGB_8888, false);
    }

    // ---- detection ----

    private List<float[]> find(Bitmap picture, int maxSide) {
        Bitmap source = argb(picture);
        int pw = source.getWidth();
        int ph = source.getHeight();
        float scale = Math.min(1f, maxSide / (float) Math.max(pw, ph));
        int w = Math.max(32, Math.round(pw * scale / 32f) * 32);
        int h = Math.max(32, Math.round(ph * scale / 32f) * 32);
        Bitmap in = Bitmap.createScaledBitmap(source, w, h, true);
        float[] map = Ncnn.detect(handle, in);
        if (in != source) in.recycle();
        if (map == null || map.length != w * h) return Collections.emptyList();
        List<float[]> boxes = boxes(map, w, h);
        float sx = pw / (float) w;
        float sy = ph / (float) h;
        for (float[] q : boxes) {
            for (int i = 0; i < 8; i += 2) {
                q[i] = clamp(q[i] * sx, 0, pw);
                q[i + 1] = clamp(q[i + 1] * sy, 0, ph);
            }
        }
        return boxes;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Text lines in the detection map, as reading-order quads in map coordinates, top to bottom. */
    static List<float[]> boxes(float[] map, int w, int h) {
        int n = w * h;
        byte[] mask = new byte[n];
        for (int i = 0; i < n; i++) if (map[i] > PIXEL_THRESHOLD) mask[i] = 1;
        int[] stack = new int[n];
        int[] rowMin = new int[h];
        int[] rowMax = new int[h];
        int[] rowStamp = new int[h];
        Arrays.fill(rowStamp, -1);
        List<float[]> found = new ArrayList<>();
        float[] points = new float[0];
        int component = 0;
        for (int start = 0; start < n && found.size() < MAX_LINES; start++) {
            if (mask[start] != 1) continue;
            component++;
            int sp = 0;
            stack[sp++] = start;
            mask[start] = 2;
            int minY = h;
            int maxY = -1;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % w;
                int y = p / w;
                if (rowStamp[y] != component) {
                    rowStamp[y] = component;
                    rowMin[y] = x;
                    rowMax[y] = x;
                } else {
                    if (x < rowMin[y]) rowMin[y] = x;
                    if (x > rowMax[y]) rowMax[y] = x;
                }
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int q = yy * w + xx;
                        if (mask[q] == 1) {
                            mask[q] = 2;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            int rows = maxY - minY + 1;
            if (points.length < rows * 4) points = new float[rows * 4];
            int count = 0;
            for (int y = minY; y <= maxY; y++) {
                if (rowStamp[y] != component) continue;
                points[count++] = rowMin[y];
                points[count++] = y;
                if (rowMax[y] != rowMin[y]) {
                    points[count++] = rowMax[y];
                    points[count++] = y;
                }
            }
            float[] hull = hull(points, count / 2);
            if (hull.length < 6) continue;
            float[] rect = minAreaRect(hull);
            if (Math.min(rect[4], rect[5]) * 2 < MIN_SIDE) continue;
            float[] corners = corners(rect, 0);
            if (score(map, w, h, corners) < BOX_THRESHOLD) continue;
            // Grow by area * ratio / perimeter on every side, like PaddleOCR's unclip.
            float bw = rect[4] * 2;
            float bh = rect[5] * 2;
            float d = bw * bh * UNCLIP_RATIO / (2 * (bw + bh));
            if (Math.min(bw, bh) + 2 * d < MIN_SIDE + 2) continue;
            found.add(readingOrder(corners(rect, d)));
        }
        joinLines(found);
        // Top to bottom (a total order: grouping into rows and paragraphs is the caller's).
        Collections.sort(found, (a, b) -> Float.compare(Math.min(a[1], a[3]), Math.min(b[1], b[3])));
        return found;
    }

    /**
     * Pieces of one text line that came out apart (words on a tilted or blurred photo): same direction and size, side
     * by side on the same baseline with at most a small gap. Each pair becomes one line, in the first one's direction.
     */
    private static void joinLines(List<float[]> quads) {
        boolean joined = true;
        while (joined) {
            joined = false;
            outer:
            for (int i = 0; i < quads.size(); i++) {
                float[] a = quads.get(i);
                float[] fa = unit(a);
                float ha = height(a);
                for (int j = 0; j < quads.size(); j++) {
                    if (i == j) continue;
                    float[] b = quads.get(j);
                    float[] fb = unit(b);
                    float hb = height(b);
                    if (fa[0] * fb[0] + fa[1] * fb[1] < 0.98f) continue;
                    if (Math.max(ha, hb) > 1.5f * Math.min(ha, hb)) continue;
                    float h = Math.max(ha, hb);
                    float va = across(a, fa, 0) + across(a, fa, 3), vb = across(b, fa, 0) + across(b, fa, 3);
                    if (Math.abs(va - vb) / 2 > 0.35f * h) continue; // not on the same baseline
                    float aEnd = Math.max(along(a, fa, 1), along(a, fa, 2));
                    float bStart = Math.min(along(b, fa, 0), along(b, fa, 3));
                    float aStart = Math.min(along(a, fa, 0), along(a, fa, 3));
                    if (bStart < aStart || bStart - aEnd > 0.6f * h) continue; // b must follow a closely
                    float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
                    for (float[] q : new float[][] {a, b}) {
                        for (int k = 0; k < 4; k++) {
                            minU = Math.min(minU, along(q, fa, k));
                            maxU = Math.max(maxU, along(q, fa, k));
                            minV = Math.min(minV, across(q, fa, k));
                            maxV = Math.max(maxV, across(q, fa, k));
                        }
                    }
                    float ux = fa[0], uy = fa[1];
                    quads.set(i, new float[] {
                            minU * ux - minV * uy, minU * uy + minV * ux,
                            maxU * ux - minV * uy, maxU * uy + minV * ux,
                            maxU * ux - maxV * uy, maxU * uy + maxV * ux,
                            minU * ux - maxV * uy, minU * uy + maxV * ux,
                    });
                    quads.remove(j);
                    joined = true;
                    break outer;
                }
            }
        }
    }

    private static float[] unit(float[] q) {
        float ux = q[2] - q[0], uy = q[3] - q[1];
        float len = (float) Math.hypot(ux, uy);
        return len == 0 ? new float[] {1, 0} : new float[] {ux / len, uy / len};
    }

    private static float along(float[] q, float[] u, int corner) {
        return q[corner * 2] * u[0] + q[corner * 2 + 1] * u[1];
    }

    private static float across(float[] q, float[] u, int corner) {
        return -q[corner * 2] * u[1] + q[corner * 2 + 1] * u[0];
    }

    /** Convex hull (monotone chain) of n points; flat array, counter-clockwise. */
    private static float[] hull(float[] pts, int n) {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> pts[a * 2] != pts[b * 2]
                ? Float.compare(pts[a * 2], pts[b * 2]) : Float.compare(pts[a * 2 + 1], pts[b * 2 + 1]));
        float[] h = new float[(2 * n + 2) * 2];
        int k = 0;
        for (int pass = 0; pass < 2; pass++) {
            int base = k;
            for (int j = 0; j < n; j++) {
                int i = order[pass == 0 ? j : n - 1 - j];
                float x = pts[i * 2];
                float y = pts[i * 2 + 1];
                while (k - base >= 2 && cross(h[(k - 2) * 2], h[(k - 2) * 2 + 1], h[(k - 1) * 2], h[(k - 1) * 2 + 1], x, y) <= 0) k--;
                h[k * 2] = x;
                h[k * 2 + 1] = y;
                k++;
            }
            k--; // the last point of each half is the first of the other
        }
        return Arrays.copyOf(h, Math.max(0, k) * 2);
    }

    private static float cross(float ox, float oy, float ax, float ay, float bx, float by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    /** Smallest rotated rectangle around a convex hull: {cx, cy, ux, uy, halfU, halfV}, v = u turned 90 degrees. */
    private static float[] minAreaRect(float[] hull) {
        int n = hull.length / 2;
        float best = Float.MAX_VALUE;
        float[] rect = new float[6];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float ex = hull[j * 2] - hull[i * 2];
            float ey = hull[j * 2 + 1] - hull[i * 2 + 1];
            float len = (float) Math.hypot(ex, ey);
            if (len == 0) continue;
            ex /= len;
            ey /= len;
            float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
            for (int k = 0; k < n; k++) {
                float px = hull[k * 2];
                float py = hull[k * 2 + 1];
                float u = px * ex + py * ey;
                float v = -px * ey + py * ex;
                if (u < minU) minU = u;
                if (u > maxU) maxU = u;
                if (v < minV) minV = v;
                if (v > maxV) maxV = v;
            }
            float area = (maxU - minU) * (maxV - minV);
            if (area < best) {
                best = area;
                float cu = (minU + maxU) / 2;
                float cv = (minV + maxV) / 2;
                rect[0] = cu * ex - cv * ey;
                rect[1] = cu * ey + cv * ex;
                rect[2] = ex;
                rect[3] = ey;
                rect[4] = (maxU - minU) / 2;
                rect[5] = (maxV - minV) / 2;
            }
        }
        return rect;
    }

    /** The rectangle's four corners grown by d on every side, in no particular order. */
    private static float[] corners(float[] r, float d) {
        float ux = r[2], uy = r[3], vx = -r[3], vy = r[2];
        float a = r[4] + d, b = r[5] + d;
        return new float[] {
                r[0] - ux * a - vx * b, r[1] - uy * a - vy * b,
                r[0] + ux * a - vx * b, r[1] + uy * a - vy * b,
                r[0] + ux * a + vx * b, r[1] + uy * a + vy * b,
                r[0] - ux * a + vx * b, r[1] - uy * a + vy * b,
        };
    }

    /** Mean text probability inside a quad (PaddleOCR's "fast" box score). */
    private static float score(float[] map, int w, int h, float[] q) {
        float minX = Math.min(Math.min(q[0], q[2]), Math.min(q[4], q[6]));
        float maxX = Math.max(Math.max(q[0], q[2]), Math.max(q[4], q[6]));
        float minY = Math.min(Math.min(q[1], q[3]), Math.min(q[5], q[7]));
        float maxY = Math.max(Math.max(q[1], q[3]), Math.max(q[5], q[7]));
        int x0 = Math.max(0, (int) Math.floor(minX)), x1 = Math.min(w - 1, (int) Math.ceil(maxX));
        int y0 = Math.max(0, (int) Math.floor(minY)), y1 = Math.min(h - 1, (int) Math.ceil(maxY));
        double sum = 0;
        int count = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (inside(q, x, y)) {
                    sum += map[y * w + x];
                    count++;
                }
            }
        }
        return count == 0 ? 0 : (float) (sum / count);
    }

    private static boolean inside(float[] q, float x, float y) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float c = cross(q[i * 2], q[i * 2 + 1], q[j * 2], q[j * 2 + 1], x, y);
            if (c > 0.5f) pos = true;
            else if (c < -0.5f) neg = true;
            if (pos && neg) return false;
        }
        return true;
    }

    /**
     * PaddleOCR's corner order (by x: the left pair's upper one is top left, the right pair's upper one top right) and
     * its rule for tall lines: one 1.5 times higher than wide is read turned a quarter, so it starts at the top right.
     */
    private static float[] readingOrder(float[] c) {
        Integer[] order = {0, 1, 2, 3};
        Arrays.sort(order, (a, b) -> Float.compare(c[a * 2], c[b * 2]));
        int tl = order[0], bl = order[1];
        if (c[tl * 2 + 1] > c[bl * 2 + 1]) { int t = tl; tl = bl; bl = t; }
        int tr = order[2], br = order[3];
        if (c[tr * 2 + 1] > c[br * 2 + 1]) { int t = tr; tr = br; br = t; }
        float[] q = {c[tl * 2], c[tl * 2 + 1], c[tr * 2], c[tr * 2 + 1], c[br * 2], c[br * 2 + 1], c[bl * 2], c[bl * 2 + 1]};
        float width = Math.max(dist(q, 0, 1), dist(q, 3, 2));
        float height = Math.max(dist(q, 0, 3), dist(q, 1, 2));
        if (height >= 1.5f * width) {
            return new float[] {q[2], q[3], q[4], q[5], q[6], q[7], q[0], q[1]};
        }
        return q;
    }

    private static float dist(float[] q, int a, int b) {
        return (float) Math.hypot(q[a * 2] - q[b * 2], q[a * 2 + 1] - q[b * 2 + 1]);
    }

    static float height(float[] q) {
        return Math.max(dist(q, 0, 3), dist(q, 1, 2));
    }

    static float width(float[] q) {
        return Math.max(dist(q, 0, 1), dist(q, 3, 2));
    }

    // ---- recognition ----

    private Line readLine(Bitmap source, float[] quad) {
        float lw = width(quad);
        float lh = height(quad);
        if (lw < 1 || lh < 1) return null;
        int rw = Math.max(8, Math.min(MAX_LINE_WIDTH, (int) Math.ceil(LINE_HEIGHT * lw / lh)));
        int pw = Math.max(rw, MIN_LINE_WIDTH);
        pw = (pw + 7) / 8 * 8;
        Bitmap line = Bitmap.createBitmap(pw, LINE_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(line);
        canvas.drawColor(0xFF808080); // the padding PaddleOCR adds is 0 after normalization: mid grey
        Matrix toLine = new Matrix();
        float[] dst = {0, 0, rw, 0, rw, LINE_HEIGHT, 0, LINE_HEIGHT};
        if (!toLine.setPolyToPoly(quad, 0, dst, 0, 4)) {
            line.recycle();
            return null;
        }
        canvas.clipRect(0, 0, rw, LINE_HEIGHT);
        canvas.drawBitmap(source, toLine, new Paint(Paint.FILTER_BITMAP_FLAG));
        float[] steps = Ncnn.recognize(handle, line);
        line.recycle();
        if (steps == null || steps.length < 2) return null;

        int count = steps.length / 2;
        float stride = pw / (float) count;
        List<String> texts = new ArrayList<>();
        List<float[]> spans = new ArrayList<>(); // first step, last step, probability of each character
        int last = 0;
        for (int t = 0; t < count; t++) {
            int cls = (int) steps[t * 2];
            if (cls == last) {
                if (cls != 0 && !spans.isEmpty()) spans.get(spans.size() - 1)[1] = t;
                continue;
            }
            last = cls;
            if (cls <= 0) continue;
            String text;
            if (cls <= dictionary.length) text = dictionary[cls - 1];
            else if (cls == dictionary.length + 1) text = " ";
            else continue;
            texts.add(text);
            spans.add(new float[] {t, t, steps[t * 2 + 1]});
        }
        // Spaces at either end carry nothing.
        while (!texts.isEmpty() && texts.get(0).equals(" ")) {
            texts.remove(0);
            spans.remove(0);
        }
        while (!texts.isEmpty() && texts.get(texts.size() - 1).equals(" ")) {
            texts.remove(texts.size() - 1);
            spans.remove(spans.size() - 1);
        }
        if (texts.isEmpty()) return null;
        float sum = 0;
        for (float[] span : spans) sum += span[2];
        float score = sum / texts.size();
        if (score < LINE_SCORE) return null;
        // A stray mark on a textured background reads as a lone "-" or "." at low confidence.
        boolean anyLetter = false;
        for (String t : texts) anyLetter |= Character.isLetterOrDigit(t.codePointAt(0));
        if (!anyLetter && score < 0.9f) return null;

        int k = texts.size();
        float[] centers = new float[k];
        for (int i = 0; i < k; i++) centers[i] = Math.min(rw, ((spans.get(i)[0] + spans.get(i)[1]) / 2f + 0.5f) * stride);
        float[] bounds = new float[k + 1];
        if (k == 1) {
            bounds[0] = 0;
            bounds[1] = rw;
        } else {
            bounds[0] = Math.max(0, centers[0] - (centers[1] - centers[0]) / 2);
            for (int i = 1; i < k; i++) bounds[i] = (centers[i - 1] + centers[i]) / 2;
            bounds[k] = Math.min(rw, centers[k - 1] + (centers[k - 1] - centers[k - 2]) / 2);
        }
        // The recognizer's dictionary has full-width forms of ASCII punctuation (for Chinese and Japanese); in a line
        // of Latin text they are the ASCII ones.
        boolean latin = false;
        for (String t : texts) {
            char c = t.charAt(0);
            if (c < 0x80 && Character.isLetterOrDigit(c)) {
                latin = true;
                break;
            }
        }
        if (latin) {
            for (int i = 0; i < k; i++) {
                String t = texts.get(i);
                char c = t.charAt(0);
                if (t.length() == 1 && c >= 0xFF01 && c <= 0xFF5E) texts.set(i, String.valueOf((char) (c - 0xFEE0)));
                else if (t.length() == 1 && c == 0x3000) texts.set(i, " ");
            }
        }
        Matrix toPicture = new Matrix();
        toLine.invert(toPicture);
        List<Glyph> glyphs = new ArrayList<>(k);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < k; i++) {
            float[] q = {bounds[i], 0, bounds[i + 1], 0, bounds[i + 1], LINE_HEIGHT, bounds[i], LINE_HEIGHT};
            toPicture.mapPoints(q);
            glyphs.add(new Glyph(texts.get(i), q));
            text.append(texts.get(i));
        }
        return new Line(quad, text.toString(), score, glyphs);
    }
}
