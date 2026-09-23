package org.ungoogled.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * Drag handles for the offline "Download a map of this area?" selection box,
 * plus an honest size figure.
 *
 * Maps has no resize gesture there at all: the box is derived from the
 * camera, so the only way to change it is to zoom.  This is a transparent
 * view laid over the picker that draws the box with bold dashed edges and
 * solid corners and lets you drag any of them.  It consumes a touch ONLY when
 * that touch starts on a handle, so panning and pinch-zooming the map
 * underneath keep working exactly as before.
 *
 * Two hidden limits make the stock figure misleading.  Maps shrinks any
 * selection above a server-supplied maximum area to a centred piece of
 * itself before asking for a size, and Google ships a coarser map for a
 * bigger region.  So the box is split into the fewest cells that all stay
 * under that maximum (a cols x rows grid shaped to the box), every cell is
 * priced separately, and the sum goes on the label.  Download then fetches
 * all of them.  While that is being worked out, both the label and the pill
 * show ". .. ..." rather than any interim number.
 *
 * Coordinates: the app keeps the box twice, once in the composable's own
 * space (which draws the highlight) and once in window space (which the
 * estimate and the download use).  Both are written on every drag.
 */
public final class PickerOverlay extends View implements Runnable {

    public static final String TAG = "ungoogled-picker-box";

    private static final int NONE = -1;
    // 0..3 corners: TL TR BR BL.  4..7 edges: L T R B.
    private static final int CORNERS = 4;
    private static final String[] DOTS = { ".", "..", "..." };
    private String labelPrefix;                 // "Download will use up to ", from Maps' own string

    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint regionFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint regionLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint regionText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    private final RectF box = new RectF();      // window coords, what is selected
    private final RectF bounds = new RectF();   // window coords, the whole map card
    private final RectF start = new RectF();
    private final RectF pill = new RectF();
    private final RectF cover = new RectF();
    private final RectF measuredFor = new RectF();
    private final RectF probe = new RectF();
    private java.util.List<OfflinePicker.Region> regions = new java.util.ArrayList<OfflinePicker.Region>();
    private double[] geo;                       // latMin, lngMin, latMax, lngMax of `probe`

    private Object localState, windowState, model;
    private float offX, offY;                   // window = local + off
    private float inset;                        // the card's margin around the box
    private int grab = NONE;
    private float downX, downY;
    private boolean bound, sampled;

    private int cols = 1, rows = 1;             // the grid Download will fetch
    private int level = 0;                      // extra detail: doubles the cell count per level
    private long lastGridAt;                    // throttle for the live recount while dragging
    private boolean measuring;
    private long total = -1L;                   // MB, sum over cells, -1 = unknown
    private Object injected;                    // the aopx we last put on the label
    private Measure measure;
    private int dotTick;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable recompute = new Recompute(this);
    private final Runnable dots = new DotsTick(this);
    private final Runnable track = new Track(this);
    private double[] lastGeo;

    public PickerOverlay(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        DashPathEffect dashes = new DashPathEffect(new float[] { 9f * d, 6f * d }, 0f);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2f * d);
        edge.setColor(0xFFFFFFFF);
        edge.setPathEffect(dashes);
        edgeShadow.setStyle(Paint.Style.STROKE);
        edgeShadow.setStrokeWidth(3.5f * d);
        edgeShadow.setColor(0x66000000);
        edgeShadow.setPathEffect(dashes);
        cornerFill.setStyle(Paint.Style.FILL);
        cornerFill.setColor(0xFFFFFFFF);
        cornerLine.setStyle(Paint.Style.STROKE);
        cornerLine.setStrokeWidth(1f * d);
        cornerLine.setColor(0x73000000);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1f * d);
        grid.setColor(0xCCFFFFFF);
        grid.setPathEffect(new DashPathEffect(new float[] { 4f * d, 4f * d }, 0f));
        pillFill.setStyle(Paint.Style.FILL);
        pillFill.setColor(0xE6202124);
        pillText.setColor(0xFFFFFFFF);
        pillText.setTextSize(13f * d);
        pillText.setTextAlign(Paint.Align.CENTER);
        regionFill.setStyle(Paint.Style.FILL);
        regionFill.setColor(0x2E1A73E8);
        regionLine.setStyle(Paint.Style.STROKE);
        regionLine.setStrokeWidth(1.5f * d);
        regionLine.setColor(0xCC1A73E8);
        regionText.setColor(0xFF1A73E8);
        regionText.setTextSize(11f * d);
        coverFill.setStyle(Paint.Style.FILL);
        coverFill.setColor(0xFFFFFFFF);              // replaced by a sampled pixel, see sampleSurface()
        coverText.setColor(0xFF5F6368);
        coverText.setTextSize(18f * d);
        coverText.setTextAlign(Paint.Align.LEFT);
        labelPrefix = labelPrefix(c);
        setClickable(false);
        setFocusable(false);
    }

    /** Called from the picker's layout callback on every pass. */
    void bind(Object localState, Object windowState, Object model,
              RectF viewportLocal, RectF effectiveLocal, RectF windowRect) {
        boolean newSession = this.model != null && this.model != model;
        if (newSession) { level = 0; total = -1L; injected = null; sampled = false; geo = null; cancelMeasure(); }
        if (this.model == null || newSession) regions = OfflinePicker.offlineRegions(getContext());
        this.localState = localState;
        this.windowState = windowState;
        this.model = model;
        offX = windowRect.left - effectiveLocal.left;
        offY = windowRect.top - effectiveLocal.top;
        inset = viewportLocal.left;
        bounds.set(viewportLocal);
        bounds.offset(offX, offY);
        if (grab == NONE) box.set(windowRect);
        if (!bound) {
            bound = true;
            handler.postDelayed(this, 700L);
            handler.post(track);
        }
        if (!sampled) sampleSurface();
        if (newSession || !box.equals(measuredFor)) scheduleRecompute(300L);
        invalidate();
    }

    private void detach() {
        cancelMeasure();
        handler.removeCallbacksAndMessages(null);
        OfflinePicker.pickerReset();
        ViewGroup p = (ViewGroup) getParent();
        if (p != null) p.removeView(this);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelMeasure();
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * The liveness poll, on this class rather than an inner/anonymous one:
     * d8 8.2.2 NPEs on the MethodParameters JDK 21 emits for those.
     * Also notices when Maps has refreshed its own figure (camera moved) and
     * re-measures so the label goes back to the true total.
     */
    @Override public void run() {
        if (!OfflinePicker.pickerAlive(model)) { detach(); return; }
        if (cols * rows > 1 && !measuring && grab == NONE) {
            Object cur = OfflinePicker.pickerCurrentEstimate(model);
            if (cur != null && cur != injected) scheduleRecompute(600L);
        }
        handler.postDelayed(this, 700L);
    }

    // ---- the label cover ------------------------------------------------------

    /** Where Maps draws "Download will use up to N MB": centred, under the card. */
    private float labelY() { return bounds.bottom + inset + 32f * d; }

    /** Named class: d8 8.2.2 NPEs on anonymous-class MethodParameters. */
    private static final class Sampled implements PixelCopy.OnPixelCopyFinishedListener {
        private final PickerOverlay o; private final Bitmap bmp;
        Sampled(PickerOverlay o, Bitmap bmp) { this.o = o; this.bmp = bmp; }
        @Override public void onPixelCopyFinished(int result) {
            if (result == PixelCopy.SUCCESS) o.onSurfaceColor(bmp.getPixel(0, 0));
        }
    }

    /** Read one pixel of the sheet beside the label so the cover matches it. */
    private void sampleSurface() {
        try {
            if (!(getContext() instanceof Activity)) return;
            sampled = true;
            int x = (int) (14f * d), y = (int) labelY();
            Bitmap bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            PixelCopy.request(((Activity) getContext()).getWindow(), new Rect(x, y, x + 1, y + 1),
                    bmp, new Sampled(this, bmp), handler);
        } catch (Throwable ignored) {}
    }

    void onSurfaceColor(int argb) {
        coverFill.setColor(0xFF000000 | argb);
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        boolean light = (r * 299 + g * 587 + b * 114) / 1000 > 128;
        coverText.setColor(light ? 0xFF5F6368 : 0xFFBDC1C6);
        invalidate();
    }

    private boolean busy() { return grab != NONE || measuring; }

    /** Named class: d8 8.2.2 NPEs on anonymous-class MethodParameters. */
    private static final class DotsTick implements Runnable {
        private final PickerOverlay o;
        DotsTick(PickerOverlay o) { this.o = o; }
        @Override public void run() { o.dotTick(); }
    }

    void dotTick() {
        if (!busy()) return;
        dotTick++;
        invalidate();
        handler.postDelayed(dots, 350L);
    }

    private void startDots() {
        handler.removeCallbacks(dots);
        dotTick = 0;
        handler.post(dots);
    }

    // ---- splitting and measuring --------------------------------------------

    private void scheduleRecompute(long delay) {
        handler.removeCallbacks(recompute);
        handler.postDelayed(recompute, delay);
    }

    private void cancelMeasure() {
        if (measure != null) { measure.cancel(); measure = null; }
        measuring = false;
    }

    /** Named class: d8 8.2.2 NPEs on anonymous-class MethodParameters. */
    private static final class Recompute implements Runnable {
        private final PickerOverlay o;
        Recompute(PickerOverlay o) { this.o = o; }
        @Override public void run() { o.doRecompute(); }
    }

    private void recount() {
        int[] g = OfflinePicker.pickerAutoGrid(model, box, level);
        cols = g[0]; rows = g[1];
    }

    void doRecompute() {
        if (model == null || grab != NONE) return;
        cancelMeasure();
        measuredFor.set(box);
        OfflinePicker.pickerScaleMaxArea(model, OfflinePicker.pickerMaxFactor(getContext()));
        refreshProjection();
        regions = OfflinePicker.offlineRegions(getContext());
        recount();
        int n = cols * rows;
        OfflinePicker.log("picker: max region " + (OfflinePicker.pickerMaxAreaM2(model) / 1000000L)
                + " km2, grid " + cols + "x" + rows);
        OfflinePicker.pickerSplit(model, cols, rows, new RectF(box), offX, offY);
        if (n == 1) {
            total = -1L; injected = null;
            OfflinePicker.pickerRefresh(model);          // Maps' own figure is right here
        } else {
            measuring = true; total = -1L;
            startDots();
            measure = new Measure(this, model, new RectF(box), cols, rows);
            measure.start();
        }
        invalidate();
    }

    void onMeasured(long sum, Object injectedObj, boolean complete) {
        measuring = false;
        measure = null;
        total = sum;
        injected = injectedObj;
        if (!complete) Toast.makeText(getContext(), "Some parts could not be sized", Toast.LENGTH_SHORT).show();
        invalidate();
    }

    /**
     * Prices every cell in turn: write it into the model, ask Maps' own
     * presenter for a size, wait for the answer to land, add it up.  Runs on
     * the main thread in 100 ms ticks so nothing blocks.
     */
    private static final class Measure implements Runnable {
        private final PickerOverlay owner;
        private final Object model, state, presenter;
        private final Method estimate;
        private final RectF box;
        private final int cols, rows;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int idx = 0, ticks = 0;
        private boolean waiting, cancelled, complete = true;
        private Object lastSeen;
        private long sum = 0L;

        Measure(PickerOverlay owner, Object model, RectF box, int cols, int rows) {
            this.owner = owner; this.model = model; this.box = box; this.cols = cols; this.rows = rows;
            this.state = OfflinePicker.pickerWindowState(model);
            this.presenter = OfflinePicker.pickerPresenter(model);
            this.estimate = OfflinePicker.pickerEstimateMethod(model);
        }

        void start() { handler.post(this); }
        void cancel() { cancelled = true; handler.removeCallbacks(this); }

        @Override public void run() {
            if (cancelled) return;
            try {
                if (state == null || presenter == null || estimate == null) { finish(false); return; }
                if (waiting) {
                    Object cur = OfflinePicker.pickerCurrentEstimate(model);
                    if (cur != null && cur != lastSeen) {
                        sum += OfflinePicker.pickerEstimateMbOf(cur);
                        idx++; waiting = false;
                    } else if (++ticks > 40) {                  // 4 s, give up on this cell
                        complete = false; idx++; waiting = false;
                    }
                }
                if (!waiting) {
                    if (idx >= cols * rows) { finish(true); return; }
                    lastSeen = OfflinePicker.pickerCurrentEstimate(model);
                    OfflinePicker.pickerSet(state, OfflinePicker.pickerCell(box, cols, rows, idx));
                    estimate.invoke(presenter);
                    waiting = true; ticks = 0;
                }
                handler.postDelayed(this, 100L);
            } catch (Throwable t) {
                OfflinePicker.log("picker: measure failed: " + t);
                finish(false);
            }
        }

        private void finish(boolean ok) {
            if (cancelled) return;
            OfflinePicker.pickerSet(state, new RectF(box));            // the whole box again
            Object mine = ok ? OfflinePicker.pickerInject(model, sum) : null;
            OfflinePicker.log("picker: " + cols + "x" + rows + " cells = " + sum + " MB" + (ok && complete ? "" : " (incomplete)"));
            owner.onMeasured(sum, mine, ok && complete);
        }
    }

    // ---- downloaded regions ---------------------------------------------------

    /** A small central piece of the card, small enough never to be clamped. */
    private void refreshProjection() {
        float w = bounds.width() / 8f, h = bounds.height() / 8f;
        probe.set(bounds.centerX() - w / 2f, bounds.centerY() - h / 2f, bounds.centerX() + w / 2f, bounds.centerY() + h / 2f);
        geo = OfflinePicker.pickerGeoOf(model, probe);
    }

    /** Named class: d8 8.2.2 NPEs on anonymous-class MethodParameters. */
    private static final class Track implements Runnable {
        private final PickerOverlay o;
        Track(PickerOverlay o) { this.o = o; }
        @Override public void run() { o.trackTick(); }
    }

    /**
     * Re-derive the geo -> screen mapping about 30 times a second so the
     * downloaded regions follow the map while it is being panned or zoomed,
     * instead of jumping into place once the camera settles.  One local
     * converter call per tick; redraws only when the camera actually moved.
     */
    void trackTick() {
        if (model != null && !bounds.isEmpty()) {
            refreshProjection();
            if (geo != null && (lastGeo == null
                    || geo[0] != lastGeo[0] || geo[1] != lastGeo[1] || geo[2] != lastGeo[2] || geo[3] != lastGeo[3])) {
                lastGeo = geo.clone();
                invalidate();
            }
        }
        handler.postDelayed(track, 33L);
    }

    private static double merc(double latDeg) { return Math.log(Math.tan(Math.PI / 4d + Math.toRadians(latDeg) / 2d)); }

    private float xOf(double lng) { return (float) (probe.left + (lng - geo[1]) / (geo[3] - geo[1]) * probe.width()); }
    private float yOf(double lat) { return (float) (probe.bottom - (merc(lat) - merc(geo[0])) / (merc(geo[2]) - merc(geo[0])) * probe.height()); }

    private void drawRegions(Canvas canvas) {
        if (geo == null || regions.isEmpty()) return;
        if (geo[3] - geo[1] <= 0 || merc(geo[2]) - merc(geo[0]) <= 0) return;
        canvas.save();
        canvas.clipRect(bounds);
        RectF r = new RectF();
        for (OfflinePicker.Region g : regions) {
            r.set(xOf(g.lngMin), yOf(g.latMax), xOf(g.lngMax), yOf(g.latMin));
            if (r.right < bounds.left || r.left > bounds.right || r.bottom < bounds.top || r.top > bounds.bottom) continue;
            canvas.drawRect(r, regionFill);
            canvas.drawRect(r, regionLine);
            if (g.name.length() > 0 && r.width() > 40f * d && r.height() > 18f * d)
                canvas.drawText(g.name, r.left + 4f * d, r.top + 12f * d, regionText);
        }
        canvas.restore();
    }

    // ---- drawing ------------------------------------------------------------

    private float hx(int i) {
        switch (i) {
            case 0: case 3: case 4: return box.left;
            case 1: case 2: case 6: return box.right;
            default: return box.centerX();
        }
    }

    private float hy(int i) {
        switch (i) {
            case 0: case 1: case 5: return box.top;
            case 2: case 3: case 7: return box.bottom;
            default: return box.centerY();
        }
    }

    private String dotsNow() { return DOTS[dotTick % DOTS.length]; }

    private String pillLabel() {
        int n = cols * rows;
        if (n == 1 && !busy()) return "1 map";
        String head = n == 1 ? "1 map" : cols + " x " + rows + " maps";
        if (busy()) return head + "   " + dotsNow();
        if (total >= 0) return head + "   " + (total + (5 - total % 5)) + " MB";   // Maps' own rounding
        return head;
    }

    private void drawPill(Canvas canvas) {
        String label = pillLabel();
        float pad = 10f * d, h = 30f * d;
        float w = pillText.measureText(label) + pad * 2f;
        float l = box.left + 10f * d, t = box.top + 10f * d;
        if (l + w > box.right) l = box.right - w;
        pill.set(l, t, l + w, t + h);
        canvas.drawRoundRect(pill, h / 2f, h / 2f, pillFill);
        canvas.drawText(label, pill.centerX(),
                pill.centerY() - (pillText.descent() + pillText.ascent()) / 2f, pillText);
    }

    /** Maps' own "Download will use up to %,d MB" with the number part cut off. */
    private static String labelPrefix(Context c) {
        String fallback = "Download will use up to ";
        try {
            int id = c.getResources().getIdentifier("OFFLINE_ACCURATE_DOWNLOAD_SIZE_EGMM", "string", c.getPackageName());
            if (id == 0) return fallback;
            String full = c.getResources().getString(id);
            int cut = full.indexOf('%');
            return cut > 0 ? full.substring(0, cut) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Hide Maps' own number while it is stale or being summed: keep its words,
     * animate one, two, three dots where the number goes.  The words stay put
     * because the text is laid out for the three-dot width and left-aligned.
     */
    private void drawCover(Canvas canvas) {
        float cy = labelY(), half = 22f * d, w = getWidth() * 0.9f;
        cover.set((getWidth() - w) / 2f, cy - half, (getWidth() + w) / 2f, cy + half);
        canvas.drawRect(cover, coverFill);
        float x0 = (getWidth() - coverText.measureText(labelPrefix + DOTS[DOTS.length - 1])) / 2f;
        canvas.drawText(labelPrefix + dotsNow(), x0,
                cy - (coverText.descent() + coverText.ascent()) / 2f, coverText);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (box.isEmpty()) return;
        drawRegions(canvas);
        canvas.drawRect(box, edgeShadow);
        canvas.drawRect(box, edge);
        for (int i = 1; i < cols; i++) {
            float fx = box.left + box.width() * i / cols;
            canvas.drawLine(fx, box.top, fx, box.bottom, grid);
        }
        for (int i = 1; i < rows; i++) {
            float fy = box.top + box.height() * i / rows;
            canvas.drawLine(box.left, fy, box.right, fy, grid);
        }
        drawPill(canvas);
        float r = 4.5f * d;
        for (int i = 0; i < CORNERS; i++) {
            float cx = hx(i), cy = hy(i);
            canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, 2f * d, 2f * d, cornerFill);
            canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, 2f * d, 2f * d, cornerLine);
        }
        if (busy()) drawCover(canvas);
    }

    // ---- touch --------------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (!OfflinePicker.pickerAlive(model)) { detach(); return false; }
                if (pill.contains(e.getX(), e.getY())) {
                    level = level >= 2 ? 0 : level + 1;
                    recount();
                    Toast.makeText(getContext(), cols + " x " + rows + " maps", Toast.LENGTH_SHORT).show();
                    scheduleRecompute(0L);
                    invalidate();
                    return true;
                }
                int hit = hitTest(e.getX(), e.getY());
                if (hit == NONE) return false;              // let the map have it
                cancelMeasure();
                handler.removeCallbacks(recompute);
                grab = hit;
                downX = e.getX(); downY = e.getY();
                start.set(box);
                startDots();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (grab == NONE) return false;
                apply(e.getX() - downX, e.getY() - downY);
                push();
                // recount the grid live so it always shows what Download would fetch
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastGridAt > 80L) {
                    lastGridAt = now;
                    recount();
                    total = -1L;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (grab == NONE) return false;
                grab = NONE;
                push();
                // let the picker's own layout pass settle first: it re-runs on
                // the state write and would otherwise race the estimate call
                scheduleRecompute(250L);
                invalidate();
                return true;
            }
        }
        return false;
    }

    /** Corners win; otherwise anywhere along an edge line grabs that edge. */
    private int hitTest(float x, float y) {
        float slop = 26f * d, best = slop;
        int found = NONE;
        for (int i = 0; i < CORNERS; i++) {
            float dist = (float) Math.hypot(x - hx(i), y - hy(i));
            if (dist <= best) { best = dist; found = i; }
        }
        if (found != NONE) return found;
        float e = 22f * d;
        boolean inY = y > box.top - e && y < box.bottom + e;
        boolean inX = x > box.left - e && x < box.right + e;
        if (inY && Math.abs(x - box.left) <= e) return 4;
        if (inY && Math.abs(x - box.right) <= e) return 6;
        if (inX && Math.abs(y - box.top) <= e) return 5;
        if (inX && Math.abs(y - box.bottom) <= e) return 7;
        return NONE;
    }

    private void apply(float dx, float dy) {
        float min = 64f * d;
        float l = start.left, t = start.top, r = start.right, b = start.bottom;
        switch (grab) {
            case 0: l += dx; t += dy; break;
            case 1: r += dx; t += dy; break;
            case 2: r += dx; b += dy; break;
            case 3: l += dx; b += dy; break;
            case 4: l += dx; break;
            case 5: t += dy; break;
            case 6: r += dx; break;
            case 7: b += dy; break;
        }
        if (l < bounds.left) l = bounds.left;
        if (t < bounds.top) t = bounds.top;
        if (r > bounds.right) r = bounds.right;
        if (b > bounds.bottom) b = bounds.bottom;
        if (r - l < min) { if (grab == 1 || grab == 2 || grab == 6) r = l + min; else l = r - min; }
        if (b - t < min) { if (grab == 2 || grab == 3 || grab == 7) b = t + min; else t = b - min; }
        box.set(l, t, r, b);
    }

    private void push() {
        RectF local = new RectF(box);
        local.offset(-offX, -offY);
        OfflinePicker.pickerRemember(local);
        OfflinePicker.pickerSet(localState, local);
        OfflinePicker.pickerSet(windowState, new RectF(box));
    }
}
