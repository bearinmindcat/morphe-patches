package org.ungoogled.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * "Better offline maps": the offline "Download a map of this area?" picker,
 * reworked. Maps derives that box from the camera, offers no way to resize it,
 * and quietly shrinks any selection above a maximum area to a centred piece of
 * itself. The patch splices two calls into the picker's layout callback --
 * pickerRect() with the box it is about to use and pickerOverlay() once it has
 * written it -- and skips the callback's own area clamp while the setting is on.
 *
 * Everything here reaches Maps' picker objects by reflection (field and method
 * names of Maps 26.36.04) and fails soft: on any error the picker behaves
 * exactly as it did before.
 */
public final class OfflinePicker {
    private OfflinePicker() {}

    /** Picker diagnostics under logcat tag UA; off in release builds. */
    static final boolean TRACE = false;
    static void log(String s) { if (TRACE) android.util.Log.i("UA", s); }

    // ---- Offline picker: a resizable selection box ------------------------
    //
    // Maps builds that box from the camera and offers no way to change it.
    // The picker's layout callback calls pickerRect() with the box it was
    // about to use, and pickerOverlay() once it has written it; between the
    // two we substitute whatever the user has dragged and put a handle
    // overlay on top.  Everything here fails soft: on any error the picker
    // behaves exactly as it did before.

    private static final RectF PICKER_VIEWPORT = new RectF();   // composable coords
    private static final RectF PICKER_EFFECTIVE = new RectF();  // composable coords
    private static RectF PICKER_USER;                           // composable coords
    private static Object PICKER_LOCAL_STATE;

    /** The box the picker should use: the user's, if they have dragged one. */
    public static RectF pickerRect(Object localState, RectF viewport) {
        if (!Shapes.BETTER_OFFLINE) { PICKER_USER = null; return viewport; }     // stock behaviour
        try {
            PICKER_LOCAL_STATE = localState;
            RectF out;
            if (PICKER_USER == null
                    || Math.abs(PICKER_VIEWPORT.width() - viewport.width()) > 1f
                    || Math.abs(PICKER_VIEWPORT.height() - viewport.height()) > 1f) {
                PICKER_USER = null;                 // first pass, or the screen changed
                out = viewport;
            } else {
                out = new RectF(PICKER_USER);
                if (out.left < viewport.left) out.left = viewport.left;
                if (out.top < viewport.top) out.top = viewport.top;
                if (out.right > viewport.right) out.right = viewport.right;
                if (out.bottom > viewport.bottom) out.bottom = viewport.bottom;
            }
            PICKER_VIEWPORT.set(viewport);
            PICKER_EFFECTIVE.set(out);
            return out;
        } catch (Throwable t) {
            return viewport;
        }
    }

    /** Put (or refresh) the handle overlay over the picker. */
    public static void pickerOverlay(Activity a, Object windowState, RectF windowRect, Object model) {
        try {
            ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
            if (root == null) return;
            View found = root.findViewWithTag(PickerOverlay.TAG);
            if (!Shapes.BETTER_OFFLINE) {                                          // stock behaviour
                if (found != null) root.removeView(found);
                pickerSplit(model, 1, 1, windowRect, 0f, 0f);
                return;
            }
            PickerOverlay v;
            if (found instanceof PickerOverlay) {
                v = (PickerOverlay) found;
                root.bringChildToFront(v);
            } else {
                v = new PickerOverlay(a);
                v.setTag(PickerOverlay.TAG);
                root.addView(v, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            v.bind(PICKER_LOCAL_STATE, windowState, model,
                   PICKER_VIEWPORT, PICKER_EFFECTIVE, windowRect);
        } catch (Throwable ignored) {}
    }

    static void pickerRemember(RectF local) {
        PICKER_USER = new RectF(local);
        PICKER_EFFECTIVE.set(local);
    }

    static void pickerReset() {
        PICKER_USER = null;
    }

    /** Write a RectF into one of the picker's two Compose states. */
    static void pickerSet(Object state, Object value) {
        try {
            if (state == null) return;
            Method m = state.getClass().getMethod("d", Object.class);
            m.setAccessible(true);
            m.invoke(state, value);
        } catch (Throwable ignored) {}
    }

    // The picker's presenter is reachable from the model: its Download
    // runnable closes over it.  d() re-runs the size estimate for whatever
    // box is in the model now; r is true only while the picker is on screen.
    private static Object presenter(Object model) throws Exception {
        Field f = model.getClass().getDeclaredField("f");
        f.setAccessible(true);
        Object runnable = f.get(model);
        if (runnable instanceof SplitDownload) runnable = ((SplitDownload) runnable).original;
        if (runnable == null) return null;
        Field a = runnable.getClass().getDeclaredField("a");
        a.setAccessible(true);
        return a.get(runnable);
    }

    static void pickerRefresh(Object model) {
        try {
            Object p = presenter(model);
            if (p == null) { log("picker: no presenter"); return; }
            try {
                Field fh = model.getClass().getDeclaredField("h");
                fh.setAccessible(true);
                Object st = fh.get(model);
                Object cur = st.getClass().getMethod("mj").invoke(st);
                log("picker: aoph.h = " + cur);
            } catch (Throwable t) { log("picker: read-back failed: " + t); }
            try {
                Method h = p.getClass().getMethod("h");   // re-runs the estimate
                h.setAccessible(true);
                h.invoke(p);
                log("picker: h() ok");
                return;
            } catch (Throwable t) {
                log("picker: h() failed: " + t);
            }
            Method d = p.getClass().getMethod("d");       // camera-moved path
            d.setAccessible(true);
            d.invoke(p);
            log("picker: d() ok");
        } catch (Throwable t) {
            log("picker: refresh failed: " + t);
        }
    }

    // ---- Multiple downloads for one selection -----------------------------
    //
    // Google scales detail to region size: one huge region comes back as a
    // coarse map.  Splitting the selection into an N x N grid and asking for
    // each cell separately gets each cell at its own (finer) detail level.
    // The picker's Download button runs a Runnable held on the model, so the
    // split simply replaces that Runnable with one that loops.

    private static Object SPLIT_ORIGINAL;

    /** 1 x 1 restores Maps' own single download. */
    static void pickerSplit(Object model, int cols, int rows, RectF windowBox, float offX, float offY) {
        try {
            Field f = model.getClass().getDeclaredField("f");
            f.setAccessible(true);
            Object current = f.get(model);
            boolean single = cols * rows <= 1;
            if (current instanceof SplitDownload) {
                if (single) { f.set(model, ((SplitDownload) current).original); SPLIT_ORIGINAL = null; return; }
                ((SplitDownload) current).set(cols, rows, windowBox);
                return;
            }
            if (single || current == null) return;
            SPLIT_ORIGINAL = current;
            SplitDownload s = new SplitDownload(model, (Runnable) current, presenter(model));
            s.set(cols, rows, windowBox);
            f.set(model, s);
        } catch (Throwable t) {
            log("picker: split install failed: " + t);
        }
    }

    /** Named class: d8 8.2.2 NPEs on anonymous-class MethodParameters. */
    static final class SplitDownload implements Runnable {
        final Object model;
        final Runnable original;
        private final Object presenter;
        private int cols = 1, rows = 1;
        private final RectF box = new RectF();

        SplitDownload(Object model, Runnable original, Object presenter) {
            this.model = model; this.original = original; this.presenter = presenter;
        }

        void set(int cols, int rows, RectF windowBox) { this.cols = cols; this.rows = rows; this.box.set(windowBox); }

        @Override public void run() {
            int n = cols * rows;
            if (n <= 1 || presenter == null) { original.run(); return; }
            try {
                Field fh = model.getClass().getDeclaredField("h");
                fh.setAccessible(true);
                Object state = fh.get(model);
                Method j = presenter.getClass().getMethod("j", String.class);
                j.setAccessible(true);
                int made = 0;
                for (int i = 0; i < n; i++) {
                    pickerSet(state, pickerCell(box, cols, rows, i));
                    j.invoke(presenter, "");
                    made++;
                }
                pickerSet(state, new RectF(box));
                log("picker: queued " + made + " downloads");
                try {
                    Field fa = model.getClass().getDeclaredField("a");
                    fa.setAccessible(true);
                    android.content.Context ctx = (android.content.Context) fa.get(model);
                    android.widget.Toast.makeText(ctx, "Downloading " + made + " maps",
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            } catch (Throwable t) {
                log("picker: split failed, falling back: " + t);
                original.run();
            }
        }
    }

    /**
     * Google's own estimate for the current box, in MB, or -1.
     * Multiplied by the cell count it is a fair total for a split download,
     * because that estimate is close to a per-region budget rather than a
     * function of area: the same camera gave 225 MB for a box and 230 MB for
     * a quarter of it.
     */
    static long pickerEstimateMb(Object model) {
        try {
            Field fd = model.getClass().getDeclaredField("d");
            fd.setAccessible(true);
            Object state = fd.get(model);
            Object value = state.getClass().getMethod("mj").invoke(state);
            if (value == null) return -1L;
            Field b = value.getClass().getDeclaredField("b");
            b.setAccessible(true);
            return b.getLong(value);
        } catch (Throwable t) {
            return -1L;
        }
    }

    // ---- Auto-split by Google's maximum region area -----------------------
    //
    // The rect -> bounds converter (aope.C, an aoqa) silently shrinks any
    // selection whose area exceeds a server-supplied maximum, keeping the
    // centre.  So a big box downloads a centred piece of itself and the
    // estimate describes that piece.  The fix: find the smallest k such that
    // no cell of a k x k grid gets shrunk (each call is local, no RPC), then
    // ask Google for every cell and sum.  The sum is what a Download of all
    // k*k regions will actually fetch.

    private static Object converter(Object presenter) throws Exception {
        Field c = presenter.getClass().getDeclaredField("C");
        c.setAccessible(true);
        return c.get(presenter);
    }

    private static Object camera(Object presenter) throws Exception {
        Field a = presenter.getClass().getDeclaredField("a");
        a.setAccessible(true);
        Object provider = a.get(presenter);
        Method get = provider.getClass().getMethod("a");
        get.setAccessible(true);
        return get.invoke(provider);
    }

    /** Google's maximum region area, in m^2, or -1. */
    static long pickerMaxAreaM2(Object model) {
        try {
            Object conv = converter(presenter(model));
            Field b = conv.getClass().getDeclaredField("b");
            b.setAccessible(true);
            return b.getLong(conv);
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static long PICKER_STOCK_MAX = -1L;

    /**
     * Raise (or restore) the converter's maximum region area by a factor.
     * The field is private final, but instance finals are settable through
     * reflection on ART.  Used to find out how big a region the server will
     * actually accept -- the client limit is not necessarily the server's.
     */
    static void pickerScaleMaxArea(Object model, double factor) {
        try {
            Object conv = converter(presenter(model));
            Field b = conv.getClass().getDeclaredField("b");
            b.setAccessible(true);
            if (PICKER_STOCK_MAX < 0) PICKER_STOCK_MAX = b.getLong(conv);
            b.setLong(conv, (long) (PICKER_STOCK_MAX * factor));
        } catch (Throwable t) {
            log("picker: max-area scale failed: " + t);
        }
    }

    /**
     * How far past the client's own area cap a single region may go, so a
     * selection needs the fewest maps possible.  Google's server priced and
     * delivered regions well above the client's 117 649 km² (see tracks.md
     * 6v); this is the largest factor verified end to end by an actual
     * download.  Prefs "picker_max_factor" overrides it for experiments.
     */
    static final double PICKER_FEWEST_FACTOR = 4d;

    static double pickerMaxFactor(Context c) {
        try {
            float f = c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE).getFloat("picker_max_factor", -1f);
            return f > 0f ? f : PICKER_FEWEST_FACTOR;
        } catch (Throwable t) {
            return PICKER_FEWEST_FACTOR;
        }
    }

    /** Area in m^2 of the region Maps would use for this window rect, or -1. */
    static double pickerAreaM2(Object model, RectF windowRect) {
        try {
            Object p = presenter(model);
            Object conv = converter(p), cam = camera(p);
            Method m = null;
            for (Method cand : conv.getClass().getDeclaredMethods()) {
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length == 2 && pt[1] == RectF.class) { m = cand; break; }
            }
            if (m == null) return -1d;
            m.setAccessible(true);
            Object bounds = m.invoke(conv, cam, windowRect);
            if (bounds == null) return -1d;
            Method area = bounds.getClass().getMethod("a");
            area.setAccessible(true);
            double sr = ((Number) area.invoke(bounds)).doubleValue();
            return sr * 6371010.0d * 6371010.0d;
        } catch (Throwable t) {
            return -1d;
        }
    }

    /** Cell idx of a cols x rows grid over the box, row-major. */
    static RectF pickerCell(RectF box, int cols, int rows, int idx) {
        float w = box.width() / cols, h = box.height() / rows;
        int r = idx / cols, c = idx % cols;
        return new RectF(box.left + c * w, box.top + r * h, box.left + (c + 1) * w, box.top + (r + 1) * h);
    }

    private static boolean pickerCellFits(Object model, RectF cell, double limit) {
        double a = pickerAreaM2(model, cell);
        return a >= 0 && a < limit;
    }

    /**
     * The grid to download: {cols, rows}.  Fewest cells such that none is
     * shrunk by the converter, shaped so the cells come out closest to
     * square; each extra level doubles the cell count for more detail.
     * Every candidate is verified cell by cell with local probes.
     */
    static int[] pickerAutoGrid(Object model, RectF box, int extraLevel) {
        long max = pickerMaxAreaM2(model);
        if (max <= 0) return new int[] { 1, 1 };
        double limit = max * 0.9999d;
        // the box itself may be clamped, so measure a small piece and scale up
        double area = -1d;
        for (int k = 4; k <= 32; k *= 2) {
            double small = pickerAreaM2(model, pickerCell(box, k, k, k + 1));   // an interior cell
            if (small < 0) return new int[] { 1, 1 };
            if (small < limit) { area = small * k * k; break; }
        }
        if (area < 0) return new int[] { 8, 8 };
        int need = Math.max(1, (int) Math.ceil(area / limit)) << extraLevel;
        for (int attempt = 0; attempt < 6; attempt++, need++) {
            int bestCols = need, bestRows = 1;
            double bestScore = Double.MAX_VALUE;
            for (int cols = 1; cols <= need; cols++) {
                int rows = (need + cols - 1) / cols;
                double cellAspect = (box.width() / cols) / (box.height() / rows);
                double score = cols * rows * 1000d + Math.abs(Math.log(cellAspect));
                if (score < bestScore) { bestScore = score; bestCols = cols; bestRows = rows; }
            }
            boolean ok = true;
            for (int i = 0; i < bestCols * bestRows && ok; i++)
                ok = pickerCellFits(model, pickerCell(box, bestCols, bestRows, i), limit);
            if (ok) return new int[] { bestCols, bestRows };
        }
        return new int[] { 8, 8 };
    }

    /** The estimate object currently on the model (an aopx), or null. */
    static Object pickerCurrentEstimate(Object model) {
        try {
            Field fd = model.getClass().getDeclaredField("d");
            fd.setAccessible(true);
            Object state = fd.get(model);
            return state.getClass().getMethod("mj").invoke(state);
        } catch (Throwable t) {
            return null;
        }
    }

    static long pickerEstimateMbOf(Object aopx) {
        try {
            Field b = aopx.getClass().getDeclaredField("b");
            b.setAccessible(true);
            return b.getLong(aopx);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Put our own total on the picker's label; returns the object written. */
    static Object pickerInject(Object model, long mb) {
        try {
            Object cur = pickerCurrentEstimate(model);
            if (cur == null) return null;
            Object mine = cur.getClass().getDeclaredConstructor(boolean.class, long.class)
                    .newInstance(true, mb);
            Field fd = model.getClass().getDeclaredField("d");
            fd.setAccessible(true);
            pickerSet(fd.get(model), mine);
            return mine;
        } catch (Throwable t) {
            log("picker: inject failed: " + t);
            return null;
        }
    }

    static Object pickerWindowState(Object model) {
        try {
            Field fh = model.getClass().getDeclaredField("h");
            fh.setAccessible(true);
            return fh.get(model);
        } catch (Throwable t) {
            return null;
        }
    }

    static Method pickerEstimateMethod(Object model) {
        try {
            Method h = presenter(model).getClass().getMethod("h");
            h.setAccessible(true);
            return h;
        } catch (Throwable t) {
            return null;
        }
    }

    static Object pickerPresenter(Object model) {
        try { return presenter(model); } catch (Throwable t) { return null; }
    }

    // ---- Downloaded regions, drawn on the picker map ------------------------
    //
    // Maps keeps its offline regions in files/offlinedatabase/offline.db,
    // table `regions`, column `serialized_region`: a proto whose field 26 is
    // the name and whose field 15 -> 1 -> 1 holds two corner points, each
    // {1: lat, 2: lng} as zigzag-encoded E6.  Read once per picker session.

    /** {latMin, lngMin, latMax, lngMax, name} per region, geo degrees. */
    static final class Region {
        final double latMin, lngMin, latMax, lngMax; final String name;
        Region(double a, double b, double c, double d, String n) { latMin = a; lngMin = b; latMax = c; lngMax = d; name = n; }
    }

    static java.util.List<Region> offlineRegions(Context c) {
        java.util.ArrayList<Region> out = new java.util.ArrayList<Region>();
        android.database.sqlite.SQLiteDatabase db = null;
        android.database.Cursor cur = null;
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "offlinedatabase/offline.db");
            if (!f.exists()) return out;
            db = android.database.sqlite.SQLiteDatabase.openDatabase(f.getPath(), null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY | android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            cur = db.rawQuery("select serialized_region from regions", null);
            while (cur.moveToNext()) {
                Region r = parseRegion(cur.getBlob(0));
                if (r != null) out.add(r);
            }
        } catch (Throwable t) {
            log("picker: regions: " + t);
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ignored) {}
            try { if (db != null) db.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    // -- a minimal protobuf walker: enough to reach the two corners and the name
    private static final class Pb {
        final byte[] b; int i, end;
        Pb(byte[] b, int i, int end) { this.b = b; this.i = i; this.end = end; }
        long varint() { long v = 0; int s = 0; while (i < end) { int c = b[i++] & 0xFF; v |= (long) (c & 0x7F) << s; s += 7; if ((c & 0x80) == 0) break; } return v; }
        /** Returns the sub-range of field `want` (wire type 2), or null. Leaves position after it. */
        int[] find(int want) {
            while (i < end) {
                long key = varint(); int f = (int) (key >>> 3), t = (int) (key & 7);
                if (t == 0) { long v = varint(); if (f == want) return new int[] { (int) v, -1 }; }
                else if (t == 1) i += 8;
                else if (t == 5) i += 4;
                else if (t == 2) { int len = (int) varint(); int s = i; i += len; if (f == want) return new int[] { s, s + len }; }
                else return null;
            }
            return null;
        }
    }

    private static double zz(long v) { return ((v >>> 1) ^ -(v & 1)) / 1e6d; }

    private static double[] point(byte[] b, int s, int e) {
        Pb p = new Pb(b, s, e);
        double lat = Double.NaN, lng = Double.NaN;
        while (p.i < p.end) {
            long key = p.varint(); int f = (int) (key >>> 3), t = (int) (key & 7);
            if (t != 0) return null;
            long v = p.varint();
            if (f == 1) lat = zz(v); else if (f == 2) lng = zz(v);
        }
        return Double.isNaN(lat) || Double.isNaN(lng) ? null : new double[] { lat, lng };
    }

    static Region parseRegion(byte[] b) {
        try {
            Pb top = new Pb(b, 0, b.length);
            int[] f15 = top.find(15); if (f15 == null || f15[1] < 0) return null;
            String name = "";
            int[] f26 = new Pb(b, f15[1], b.length).find(26);
            if (f26 != null && f26[1] >= 0) name = new String(b, f26[0], f26[1] - f26[0], "UTF-8");
            int[] f1 = new Pb(b, f15[0], f15[1]).find(1); if (f1 == null || f1[1] < 0) return null;
            int[] f11 = new Pb(b, f1[0], f1[1]).find(1); if (f11 == null || f11[1] < 0) return null;
            Pb box = new Pb(b, f11[0], f11[1]);
            int[] c1 = box.find(1); if (c1 == null || c1[1] < 0) return null;
            int[] c2 = box.find(2); if (c2 == null || c2[1] < 0) return null;
            double[] a = point(b, c1[0], c1[1]), z = point(b, c2[0], c2[1]);
            if (a == null || z == null) return null;
            return new Region(Math.min(a[0], z[0]), Math.min(a[1], z[1]), Math.max(a[0], z[0]), Math.max(a[1], z[1]), name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Geo -> screen for the picker map: {latMin, lngMin, latMax, lngMax} of a
     * small interior rect, from Maps' own converter, gives an affine map in
     * lng and a Mercator map in lat.  Returns null if it cannot be derived.
     */
    static double[] pickerGeoOf(Object model, RectF windowRect) {
        try {
            Object p = presenter(model);
            Object conv = converter(p), cam = camera(p);
            Method m = null;
            for (Method cand : conv.getClass().getDeclaredMethods()) {
                Class<?>[] pt = cand.getParameterTypes();
                if (pt.length == 2 && pt[1] == RectF.class) { m = cand; break; }
            }
            if (m == null) return null;
            m.setAccessible(true);
            Object bounds = m.invoke(conv, cam, windowRect);
            if (bounds == null) return null;
            Field fa = bounds.getClass().getDeclaredField("a"), fb = bounds.getClass().getDeclaredField("b");
            fa.setAccessible(true); fb.setAccessible(true);
            Object sw = fa.get(bounds), ne = fb.get(bounds);
            Field la = sw.getClass().getDeclaredField("a"), lo = sw.getClass().getDeclaredField("b");
            la.setAccessible(true); lo.setAccessible(true);
            return new double[] { la.getDouble(sw), lo.getDouble(sw), la.getDouble(ne), lo.getDouble(ne) };
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean pickerAlive(Object model) {
        if (model == null) return false;
        try {
            Object p = presenter(model);
            if (p == null) return true;          // not wired up yet, assume alive
            Field r = p.getClass().getDeclaredField("r");
            r.setAccessible(true);
            return r.getBoolean(p);
        } catch (Throwable t) {
            return true;                          // never remove on a lookup failure
        }
    }
}
