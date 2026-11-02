package org.ungoogled.samsunggallery.ocr;

import android.graphics.Point;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes recognized lines into Samsung's OCR result (com.samsung.android.sdk.ocr.OCRResult, in Gallery): blocks
 * (paragraphs) of lines of words of characters, each with its four corners. Gallery's selection, copy and share work
 * from exactly this. Built by reflection: the classes are Gallery's own.
 */
final class SamsungResult {
    private static final String OCR = "com.samsung.android.sdk.ocr.OCRResult";

    private SamsungResult() {
    }

    static void fill(Object result, List<TextReader.Line> lines) throws ReflectiveOperationException {
        ClassLoader loader = result.getClass().getClassLoader();
        Class<?> blockType = Class.forName(OCR + "$BlockData", true, loader);
        Class<?> lineType = Class.forName(OCR + "$LineData", true, loader);
        Class<?> wordType = Class.forName(OCR + "$WordData", true, loader);
        Class<?> charType = Class.forName(OCR + "$CharData", true, loader);
        // Declared on the common base: the subclasses' own (bridge) overrides only take their own type.
        Class<?> baseType = Class.forName(OCR + "$AbsData", true, loader);
        Method setRect = baseType.getMethod("setRect", Point[].class);
        Method setAngle = baseType.getMethod("setAngle", float.class);
        Method addBlock = result.getClass().getMethod("add", blockType);
        Method addLine = blockType.getMethod("add", lineType);
        Method addWord = lineType.getMethod("add", wordType);
        Method addChar = wordType.getMethod("add", charType);
        Method setPoly = wordType.getMethod("setPoly", Point[].class);
        Method setText = charType.getMethod("setText", String.class);

        for (List<TextReader.Line> paragraph : paragraphs(lines)) {
            Object block = blockType.getDeclaredConstructor().newInstance();
            setRect.invoke(block, (Object) points(bounds(paragraph)));
            setAngle.invoke(block, angle(paragraph.get(0).quad));
            for (TextReader.Line line : paragraph) {
                Object lineData = lineType.getDeclaredConstructor().newInstance();
                setRect.invoke(lineData, (Object) points(line.quad));
                setAngle.invoke(lineData, angle(line.quad));
                List<TextReader.Glyph> word = new ArrayList<>();
                for (int i = 0; i <= line.glyphs.size(); i++) {
                    TextReader.Glyph glyph = i < line.glyphs.size() ? line.glyphs.get(i) : null;
                    if (glyph != null && !glyph.text.trim().isEmpty()) {
                        word.add(glyph);
                        continue;
                    }
                    if (word.isEmpty()) continue;
                    float[] first = word.get(0).quad;
                    float[] last = word.get(word.size() - 1).quad;
                    float[] wq = {first[0], first[1], last[2], last[3], last[4], last[5], first[6], first[7]};
                    Object wordData = wordType.getDeclaredConstructor().newInstance();
                    setRect.invoke(wordData, (Object) points(wq));
                    setPoly.invoke(wordData, (Object) points(wq));
                    setAngle.invoke(wordData, angle(wq));
                    for (TextReader.Glyph g : word) {
                        Object charData = charType.getDeclaredConstructor().newInstance();
                        setText.invoke(charData, g.text);
                        setRect.invoke(charData, (Object) points(g.quad));
                        setAngle.invoke(charData, angle(g.quad));
                        addChar.invoke(wordData, charData);
                    }
                    addWord.invoke(lineData, wordData);
                    word.clear();
                }
                addLine.invoke(block, lineData);
            }
            addBlock.invoke(result, block);
        }
        Object info = result.getClass().getMethod("getProcessInfo").invoke(result);
        if (info != null) info.getClass().getMethod("setHasText", boolean.class).invoke(info, !lines.isEmpty());
    }

    private static Point[] points(float[] q) {
        return new Point[] {
                new Point(Math.round(q[0]), Math.round(q[1])), new Point(Math.round(q[2]), Math.round(q[3])),
                new Point(Math.round(q[4]), Math.round(q[5])), new Point(Math.round(q[6]), Math.round(q[7])),
        };
    }

    private static float angle(float[] q) {
        return (float) Math.toDegrees(Math.atan2(q[3] - q[1], q[2] - q[0]));
    }

    /** Reading direction (unit vector) and text height of a line. */
    private static float[] frame(float[] q) {
        float ux = q[2] - q[0], uy = q[3] - q[1];
        float len = (float) Math.hypot(ux, uy);
        if (len == 0) return new float[] {1, 0, TextReader.height(q)};
        return new float[] {ux / len, uy / len, TextReader.height(q)};
    }

    /**
     * Paragraphs: Samsung's text joins the lines of a block with spaces, so a block holds only lines that wrap into each
     * other. A line continues into the one right under it when both run the same way at the same size, they start at
     * the same place (or the first is indented), the first is a long line that reaches the column's end, and the next
     * does not start a list item. Headings, list items, addresses and receipt lines stay lines of their own.
     */
    static List<List<TextReader.Line>> paragraphs(List<TextReader.Line> lines) {
        int n = lines.size();
        int[] parent = new int[n];
        boolean[] continued = new boolean[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            float[] qi = lines.get(i).quad;
            float[] fi = frame(qi);
            float hi = fi[2];
            float iu0 = qi[0] * fi[0] + qi[1] * fi[1], iu1 = qi[2] * fi[0] + qi[3] * fi[1];
            float cix = (qi[0] + qi[4]) / 2, ciy = (qi[1] + qi[5]) / 2;
            int next = -1;
            float nextDv = Float.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                float[] qj = lines.get(j).quad;
                float[] fj = frame(qj);
                float hj = fj[2];
                if (fi[0] * fj[0] + fi[1] * fj[1] < 0.985f) continue; // more than ~10 degrees apart
                if (Math.max(hi, hj) > 1.3f * Math.min(hi, hj)) continue;
                float h = Math.max(hi, hj);
                // j's centre across the reading direction, below i by about one line
                float cjx = (qj[0] + qj[4]) / 2, cjy = (qj[1] + qj[5]) / 2;
                float dv = -(cjx - cix) * fi[1] + (cjy - ciy) * fi[0];
                if (dv < 0.6f * h || dv > 1.9f * h) continue;
                float ju0 = qj[0] * fi[0] + qj[1] * fi[1], ju1 = qj[2] * fi[0] + qj[3] * fi[1];
                if (Math.min(iu1, ju1) - Math.max(iu0, ju0) <= 0) continue; // not under it
                if (dv < nextDv) {
                    nextDv = dv;
                    next = j;
                }
            }
            if (next < 0 || continued[next]) continue;
            float[] qj = lines.get(next).quad;
            float h = Math.max(hi, frame(qj)[2]);
            float ju0 = qj[0] * fi[0] + qj[1] * fi[1], ju1 = qj[2] * fi[0] + qj[3] * fi[1];
            boolean aligned = iu0 - ju0 > -h && iu0 - ju0 < 4 * h;
            boolean wrapped = iu1 - iu0 >= 12 * hi && iu1 >= ju1 - h && !endsSentence(lines.get(i).text);
            if (aligned && wrapped && !startsItem(lines.get(next).text)) {
                continued[next] = true;
                union(parent, i, next);
            }
        }
        List<List<TextReader.Line>> groups = new ArrayList<>();
        List<Integer> roots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int r = find(parent, i);
            int k = roots.indexOf(r);
            if (k < 0) {
                roots.add(r);
                groups.add(new ArrayList<>());
                k = groups.size() - 1;
            }
            groups.get(k).add(lines.get(i));
        }
        for (List<TextReader.Line> group : groups) {
            float[] f = frame(group.get(0).quad);
            Collections.sort(group, (a, b) -> Float.compare(-a.quad[0] * f[1] + a.quad[1] * f[0], -b.quad[0] * f[1] + b.quad[1] * f[0]));
        }
        Collections.sort(groups, (a, b) -> {
            float ay = top(a), by = top(b);
            return ay != by ? Float.compare(ay, by) : Float.compare(a.get(0).quad[0], b.get(0).quad[0]);
        });
        return groups;
    }

    /** A line that ends a sentence or introduces something is taken as the end of its paragraph. */
    private static boolean endsSentence(String text) {
        String t = text.trim();
        return !t.isEmpty() && ".!?:;\u3002\uff01\uff1f\uff1a".indexOf(t.charAt(t.length() - 1)) >= 0;
    }

    /** A list item, bullet or numbered. */
    private static boolean startsItem(String text) {
        String t = text.trim();
        if (t.isEmpty()) return false;
        if ("-\u2022\u00b7*\u2013\u2014\u25cf\u25cb\u25aa\u25a0".indexOf(t.charAt(0)) >= 0) return true;
        int k = 0;
        while (k < t.length() && k < 3 && Character.isDigit(t.charAt(k))) k++;
        return k > 0 && k < t.length() && (t.charAt(k) == '.' || t.charAt(k) == ')');
    }

    private static float top(List<TextReader.Line> group) {
        float y = Float.MAX_VALUE;
        for (TextReader.Line l : group) y = Math.min(y, Math.min(l.quad[1], l.quad[3]));
        return y;
    }

    /** A paragraph's rectangle, in its first line's direction. */
    private static float[] bounds(List<TextReader.Line> group) {
        float[] f = frame(group.get(0).quad);
        float ux = f[0], uy = f[1];
        float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (TextReader.Line l : group) {
            for (int i = 0; i < 8; i += 2) {
                float u = l.quad[i] * ux + l.quad[i + 1] * uy;
                float v = -l.quad[i] * uy + l.quad[i + 1] * ux;
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }
        }
        return new float[] {
                minU * ux - minV * uy, minU * uy + minV * ux,
                maxU * ux - minV * uy, maxU * uy + minV * ux,
                maxU * ux - maxV * uy, maxU * uy + maxV * ux,
                minU * ux - maxV * uy, minU * uy + maxV * ux,
        };
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
