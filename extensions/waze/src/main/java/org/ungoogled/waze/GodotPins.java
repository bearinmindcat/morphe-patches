package org.ungoogled.waze;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Waze 5.x draws its realtime alert pins with Godot. By default (server value "Waze map use 3D realtime alerts"
 * off) they come from the 2D renderer (libwaze's alert_2d_scene.cc), which instances one of three scenes from its
 * waze_map_scenes.pck -- OverviewAlertsAnimation/SmallScene (the zoomed-out dot), MediumScene (the bubble) and
 * LargeScene (the large pin, its badge and comment) -- and sets only each scene ROOT's scale as the map zooms. The
 * pin's own size is the scale its AnimationPlayer gives the root's children (0 -> 1.01 -> 1 as it shows). This
 * reads Godot's PCK and binary resource (RSRC) formats far enough to find those keys, so they can be multiplied in
 * place in Waze's writable copy of the pack: the same number of bytes, nothing else moves.
 */
final class GodotPins {
    private GodotPins() {}

    /** The 2D alert scenes, by the end of their exported paths in the pack. */
    static final String[] SCENES = {"-SmallScene.scn", "-MediumScene.scn", "-LargeScene.scn"};
    /** Keys below this are the shrunk-to-nothing states (0, 1e-5); they stay as they are. */
    private static final float SHOWN = 0.05f;

    /** Where a file sits in the pack: its first byte from the pack's start, and its length. */
    static final class Entry {
        final long offset, size;
        Entry(long offset, long size) { this.offset = offset; this.size = size; }
        @Override public boolean equals(Object o) {
            return o instanceof Entry && ((Entry) o).offset == offset && ((Entry) o).size == size;
        }
        @Override public int hashCode() { return Long.hashCode(offset * 31 + size); }
    }

    /**
     * The pack's exported files whose paths end with one of [suffixes], read from the start of a PCK stream;
     * suffix -> where it is. Missing ones are left out.
     */
    static Map<String, Entry> find(InputStream in, String[] suffixes) throws IOException {
        Map<String, Entry> out = new HashMap<>();
        byte[] head = new byte[4 + 4 * 4];
        readFully(in, head);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        if (h.getInt(0) != 0x43504447) return out;   // "GDPC"
        int format = h.getInt(4);
        long base = 0;
        if (format >= 2) {
            byte[] more = new byte[4 + 8];             // pack flags, file base
            readFully(in, more);
            base = ByteBuffer.wrap(more).order(ByteOrder.LITTLE_ENDIAN).getLong(4);
        }
        skipFully(in, 16 * 4);                         // reserved
        byte[] four = new byte[4];
        readFully(in, four);
        int count = ByteBuffer.wrap(four).order(ByteOrder.LITTLE_ENDIAN).getInt();
        for (int i = 0; i < count; i++) {
            readFully(in, four);
            byte[] path = new byte[ByteBuffer.wrap(four).order(ByteOrder.LITTLE_ENDIAN).getInt()];
            readFully(in, path);
            byte[] rec = new byte[8 + 8 + 16 + (format >= 2 ? 4 : 0)];
            readFully(in, rec);
            ByteBuffer r = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN);
            String p = new String(path, StandardCharsets.UTF_8).replace("\0", "");
            if (!p.contains("/exported/")) continue;
            for (String s : suffixes) if (p.endsWith(s)) out.put(s, new Entry(base + r.getLong(0), r.getLong(8)));
        }
        return out;
    }

    /** One Vector2 key: where its two floats start in the scene, and their values there. */
    static final class Key {
        final int at;
        final float x, y;
        Key(int at, float x, float y) { this.at = at; this.x = x; this.y = y; }
    }

    /**
     * The shown scale keys of the scene [b] (a Godot binary PackedScene): every Vector2 key of an animation track
     * on a direct child of the root (path "node:scale" -- a grandchild already grows with its parent) at or above
     * [SHOWN].
     */
    static List<Key> keys(byte[] b) {
        Reader r = new Reader(b);
        String[] strings = r.header();
        List<Key> out = new ArrayList<>();
        for (long off : r.resources) {
            r.p = (int) off;
            if (!r.str().equals("Animation")) continue;
            int props = r.i32();
            Map<String, String> paths = new HashMap<>();
            Map<String, Object> keyValues = new HashMap<>();
            for (int i = 0; i < props; i++) {
                String name = strings[r.i32()];
                Object v = r.variant(strings);
                if (name.startsWith("tracks/") && name.endsWith("/path")) paths.put(name.substring(7, name.length() - 5), String.valueOf(v));
                else if (name.startsWith("tracks/") && name.endsWith("/keys")) keyValues.put(name.substring(7, name.length() - 5), v);
            }
            for (Map.Entry<String, String> e : paths.entrySet()) {
                String path = e.getValue();
                if (!path.endsWith(":scale") || path.indexOf('/') >= 0) continue;
                Object keys = keyValues.get(e.getKey());
                if (!(keys instanceof Map)) continue;
                Object values = ((Map<?, ?>) keys).get("values");
                if (!(values instanceof List)) continue;
                for (Object v : (List<?>) values) {
                    if (v instanceof Key && Math.abs(((Key) v).x) >= SHOWN && Math.abs(((Key) v).y) >= SHOWN) out.add((Key) v);
                }
            }
        }
        return out;
    }

    /** The two floats of [k] times [f], little-endian, as they go in the file. */
    static byte[] scaled(Key k, float f) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(k.x * f).putFloat(k.y * f).array();
    }

    /** Just enough of Godot's binary variant format to walk an Animation's properties. */
    private static final class Reader {
        final byte[] b;
        final ByteBuffer bb;
        int p;
        long[] resources = new long[0];

        Reader(byte[] b) { this.b = b; this.bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); }

        /** Reads the header and tables; returns the string table, keeps the internal resources' offsets. */
        String[] header() {
            if (b[0] != 'R' || b[1] != 'S' || b[2] != 'R' || b[3] != 'C') throw new IllegalStateException("not RSRC");
            p = 4;
            if (i32() != 0 || i32() != 0) throw new IllegalStateException("unsupported RSRC");
            i32(); i32(); i32();                       // version major, minor, format
            str();                                     // type
            p += 8;                                    // import metadata offset
            int flags = i32();
            if ((flags & 2) != 0) p += 8;              // uid
            if ((flags & 4) != 0) str();               // script class
            p += 11 * 4;
            int n = i32();
            String[] strings = new String[n];
            for (int i = 0; i < n; i++) strings[i] = str();
            int ext = i32();
            for (int i = 0; i < ext; i++) { str(); str(); if ((flags & 2) != 0) p += 8; }
            int ints = i32();
            resources = new long[ints];
            for (int i = 0; i < ints; i++) { str(); resources[i] = i64(); }
            return strings;
        }

        int i32() { int v = bb.getInt(p); p += 4; return v; }
        long i64() { long v = bb.getLong(p); p += 8; return v; }
        float f32() { float v = bb.getFloat(p); p += 4; return v; }

        String str() {
            int n = i32() & 0x7FFFFFFF;
            String s = new String(b, p, n, StandardCharsets.UTF_8);
            p += n;
            int z = s.indexOf('\0');
            return z >= 0 ? s.substring(0, z) : s;
        }

        Object variant(String[] strings) {
            int t = i32();
            switch (t) {
                case 1: case 42: case 43: return null;                       // nil, callable, signal
                case 2: case 3: case 23: return i32();                        // bool, int, rid
                case 40: return i64();
                case 4: return f32();
                case 41: p += 8; return null;
                case 5: case 44: return str();                                // string, string name
                case 10: { int at = p; return new Key(at, f32(), f32()); }    // vector2
                case 45: p += 8; return null;
                case 12: case 47: p += 12; return null;                       // vector3(i)
                case 11: case 13: case 14: case 20: case 50: case 46: case 51: p += 16; return null;
                case 15: case 18: p += 24; return null;                       // aabb, transform2d
                case 16: p += 36; return null;                                // basis
                case 17: p += 48; return null;                                // transform3d
                case 52: p += 64; return null;                                // projection
                case 22: {                                                    // node path
                    int nc = bb.getShort(p) & 0x7FFF, sc = bb.getShort(p + 2) & 0x7FFF;
                    p += 4;
                    StringBuilder s = new StringBuilder();
                    for (int i = 0; i < nc; i++) { if (i > 0) s.append('/'); s.append(strings[i32()]); }
                    for (int i = 0; i < sc; i++) s.append(':').append(strings[i32()]);
                    return s.toString();
                }
                case 24: {                                                    // object
                    int k = i32();
                    if (k == 0) return null;
                    if (k == 1) { str(); str(); return null; }
                    i32();
                    return null;
                }
                case 26: {                                                    // dictionary
                    int n = i32() & 0x7FFFFFFF;
                    Map<String, Object> d = new HashMap<>();
                    for (int i = 0; i < n; i++) {
                        Object k = variant(strings);
                        Object v = variant(strings);
                        d.put(String.valueOf(k), v);
                    }
                    return d;
                }
                case 30: {                                                    // array
                    int n = i32() & 0x7FFFFFFF;
                    List<Object> a = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) a.add(variant(strings));
                    return a;
                }
                case 31: { int n = i32(); p += n + ((4 - n % 4) % 4); return null; }
                case 32: { int n = i32(); p += 4 * n; return null; }
                case 33: { int n = i32(); p += 4 * n; return null; }
                case 48: case 49: { int n = i32(); p += 8 * n; return null; }
                case 34: { int n = i32(); for (int i = 0; i < n; i++) str(); return null; }
                case 35: { int n = i32(); p += 12 * n; return null; }
                case 37: { int n = i32(); p += 8 * n; return null; }
                case 36: case 53: { int n = i32(); p += 16 * n; return null; }
                default: throw new IllegalStateException("variant type " + t + " at " + (p - 4));
            }
        }
    }

    static void readFully(InputStream in, byte[] buf) throws IOException {
        int at = 0;
        while (at < buf.length) {
            int n = in.read(buf, at, buf.length - at);
            if (n < 0) throw new IOException("short read");
            at += n;
        }
    }

    static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("short skip");
                s = 1;
            }
            n -= s;
        }
    }

    static byte[] read(InputStream in, long n) throws IOException {
        byte[] out = new byte[(int) n];
        readFully(in, out);
        return out;
    }
}
