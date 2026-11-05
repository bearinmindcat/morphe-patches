package org.ungoogled.waze;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Average speed zone bar (issue #60): old Waze's bar on the left of the map, filling up as you drive through an
 * average speed (section control) zone, with your average speed so far, coloured against the limit.
 *
 * Waze tells Java about a zone two ways, depending on which of its own zone displays is on: the older one sends
 * the share of the zone driven and the metres left (NavigationInfoNativeManager.onAverageSpeedCamZoneUpdate, then
 * onAverageSpeedCamZoneClear), the newer one -- Waze's "insights", the countdown at the bottom -- sends the metres
 * left in the zone (InsightsInfraNativeManager.onSegmentInsightUpdated, an AVERAGE_SPEED_CAMERA segment WITHIN).
 * The patch calls this class from both. The zone's length is the metres left at the first update (worked back
 * from the share driven when Waze gives one); the average is the metres driven since then over the time taken.
 *
 * The limit is the road's, as Waze's speedometer shows it (its State's maxSpeedRoad), in the speedometer's
 * units. Green at or under the limit, orange up to the tolerance above it, red beyond: the tolerance is a share
 * of the limit and at least a number of km/h or mph (the patch's 6% and 6: Belgium's rule). Without a limit the
 * bar is Waze's blue.
 *
 * The protos are Waze's JNI ones, whose names stay from one build to the next; they are read by name.
 */
public final class ZoneBar {
    private ZoneBar() {}

    private static final String TAG = "UA";
    /** Only after `adb shell setprop log.tag.UA DEBUG` and a restart of Waze: fakes zones (see SelfTest). */
    static final String ACTION_TEST = "org.ungoogled.waze.ZONE_TEST";
    private static final int AVERAGE_SPEED_CAMERA = 7, WITHIN = 1;
    /** A zone with no word from Waze for this long is over. */
    private static final long STALE_MS = 90_000L;
    private static final int GREEN = 0xFF2EB85C, ORANGE = 0xFFFF9800, RED = 0xFFE53935, BLUE = 0xFF2F80ED;
    private static final Pattern MAX_SPEED = Pattern.compile("maxSpeedRoad=(-?\\d+)");
    private static final Pattern UNITS = Pattern.compile("units=([^,]*)");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Written on the main thread only; volatile because the car screen's pill (carPill) reads them on Waze's car thread.
    private static volatile boolean inZone;
    private static volatile long startAt, lastAt;
    private static volatile int startLeft, length, left, percent;
    /** For the self-test: the time an injected update happened at, instead of now. */
    static long virtualNow = -1;
    private static volatile int limit = -1;
    private static volatile boolean mph;
    private static ZoneView view;

    public static void init(Application a) {
        try {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                IntentFilter f = new IntentFilter(ACTION_TEST);
                if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(new SelfTest(), f, Context.RECEIVER_EXPORTED);
                else a.registerReceiver(new SelfTest(), f);
                Log.d(TAG, "zone bar: self-test receiver on, " + ACTION_TEST);
            }
        } catch (Throwable t) {
            Log.w(TAG, "zone bar", t);
        }
    }

    // ---- from the patch -------------------------------------------------------------------------

    /** NavigationInfoNativeManager.onAverageSpeedCamZoneUpdate: Waze's AverageSpeedCameraZoneUpdate. Main thread. */
    public static void legacy(Object update) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> legacy(update));
            return;
        }
        if (SelfTest.running && !SelfTest.injecting) return;
        try {
            if (update == null || !Customization.on("zoneBar")) return;
            int p = (Integer) call(update, "getPercent"), l = (Integer) call(update, "getLeftDistance");
            long now = now();
            if (inZone && byInsights) return;
            if (!inZone) {
                start(l, p > 0 && p < 100 ? Math.round(l / (1f - p / 100f)) : l, now);
                byInsights = false;
            }
            progress(Math.max(0, Math.min(100, p)), l, now);
        } catch (Throwable t) {
            Log.w(TAG, "zone bar: legacy", t);
        }
    }

    /** NavigationInfoNativeManager.onAverageSpeedCamZoneClear. Main thread. */
    public static void legacyClear() {
        // Waze 5.26 calls it from its JNI method, on Waze's native thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(ZoneBar::legacyClear);
            return;
        }
        if (SelfTest.running && !SelfTest.injecting) return;
        if (!byInsights) end("cleared");
    }

    /** InsightsInfraNativeManager.onSegmentInsightUpdated: Waze's SegmentInsightUpdate. Main thread. */
    public static void insights(Object update) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> insights(update));
            return;
        }
        if (SelfTest.running && !SelfTest.injecting) return;
        try {
            if (update == null || !Customization.on("zoneBar")) return;
            Object infos = call(update, "getInfoList");
            Integer remaining = null;
            if (infos instanceof List) {
                for (Object info : (List<?>) infos) {
                    Object id = call(info, "getId");
                    if ((Integer) call(id, "getSegmentTypeValue") != AVERAGE_SPEED_CAMERA) continue;
                    if ((Integer) call(info, "getPositionRelativeToSegmentValue") != WITHIN) continue;
                    remaining = (Integer) call(info, "getRemainingDistanceMeters");
                    break;
                }
            }
            if (remaining == null) {
                if (inZone && byInsights) end("no zone in Waze's update");
                return;
            }
            if (inZone && !byInsights) return;
            long now = now();
            if (!inZone) {
                start(remaining, remaining, now);
                byInsights = true;
            }
            int p = length > 0 ? Math.round(100f * (length - remaining) / length) : 0;
            progress(Math.max(0, Math.min(100, p)), remaining, now);
        } catch (Throwable t) {
            Log.w(TAG, "zone bar: insights", t);
        }
    }

    /**
     * Each speedometer state Waze makes (its State class's constructors): its toString() has the road's limit and the
     * units. Waze makes them for the phone and the car screen alike, so the limit is known with only Android Auto on.
     */
    public static void speedometer(Object state) {
        try {
            if (state == null) return;
            String s = String.valueOf(state);
            Matcher m = MAX_SPEED.matcher(s);
            if (m.find()) limit = Integer.parseInt(m.group(1));
            Matcher u = UNITS.matcher(s);
            if (u.find()) {
                String units = u.group(1).toLowerCase(Locale.ROOT);
                mph = units.contains("mph") || units.contains("mi");
            }
        } catch (Throwable t) {
            Log.w(TAG, "zone bar: speedometer", t);
        }
    }

    /** The Customization switch. */
    static void switched(boolean on) {
        if (!on) end("switched off");
    }

    // ---- the zone ---------------------------------------------------------------------------------

    private static long now() {
        return virtualNow >= 0 ? virtualNow : SystemClock.elapsedRealtime();
    }

    private static void start(int left0, int length0, long now) {
        inZone = true;
        startAt = now;
        startLeft = left0;
        length = Math.max(left0, length0);
        Log.i(TAG, "zone bar: zone entered, " + length + " m");
    }

    private static void progress(int p, int l, long now) {
        percent = p;
        left = l;
        lastAt = now;
        float avg = average();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, String.format(Locale.ROOT, "zone bar: %d%%, %d m left, average %s, limit %d %s -> %s",
                    p, l, Float.isNaN(avg) ? "-" : String.format(Locale.ROOT, "%.1f", avg), limit, mph ? "mph" : "km/h",
                    name(colour(avg))));
        }
        MAIN.removeCallbacks(STALE);
        MAIN.postDelayed(STALE, STALE_MS);
        show();
    }

    private static final Runnable STALE = () -> end("nothing from Waze for 90 s");

    /**
     * Which of Waze's two displays the zone under way came from. Each ends only its own: Waze 5.26 sends an insights
     * update about once a second while it has a location, every one without a zone when the older display has it.
     */
    private static boolean byInsights;

    private static void end(String why) {
        MAIN.removeCallbacks(STALE);
        if (inZone) Log.i(TAG, "zone bar: zone over (" + why + ")");
        inZone = false;
        hide();
    }

    /** The average speed since the zone began, in the speedometer's units; NaN until there is enough to tell. */
    static float average() {
        int driven = startLeft - left;
        float seconds = (lastAt - startAt) / 1000f;
        if (driven < 30 || seconds < 3f) return Float.NaN;
        float ms = driven / seconds;
        return mph ? ms * 2.2369363f : ms * 3.6f;
    }

    /** Green, orange or red against the road's limit and the tolerance; blue without a limit or an average. */
    static int colour(float avg) {
        int lim = limit;
        if (lim <= 0 || Float.isNaN(avg)) return BLUE;
        if (avg <= lim) return GREEN;
        float tolerance = Math.max(Customization.number(Customization.ZONE_TOLERANCE_MIN, 6),
                lim * Customization.number(Customization.ZONE_TOLERANCE_PERCENT, 6) / 100f);
        return avg <= lim + tolerance ? ORANGE : RED;
    }

    private static String name(int colour) {
        return colour == GREEN ? "green" : colour == ORANGE ? "orange" : colour == RED ? "red" : "blue";
    }

    private static String distance(int metres) {
        if (mph) {
            float miles = metres / 1609.344f;
            return miles >= 0.1f ? String.format(Locale.getDefault(), "%.1f mi", miles) : Math.round(metres * 3.28084f / 10f) * 10 + " ft";
        }
        return metres >= 1000 ? String.format(Locale.getDefault(), "%.1f km", metres / 1000f) : Math.round(metres / 10f) * 10 + " m";
    }

    // ---- on screen --------------------------------------------------------------------------------

    private static void show() {
        Activity a = Customization.activity();
        if (a == null || a.isFinishing()) return;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (view != null && view.getParent() != decor) hide();
        if (view == null) {
            view = new ZoneView(a);
            decor.addView(view, place(a, decor));
        } else {
            FrameLayout.LayoutParams lp = place(a, decor), now = (FrameLayout.LayoutParams) view.getLayoutParams();
            if (lp.width != now.width || lp.height != now.height || lp.gravity != now.gravity
                    || lp.leftMargin != now.leftMargin || lp.topMargin != now.topMargin || lp.rightMargin != now.rightMargin) {
                view.setLayoutParams(lp);
            }
        }
        view.night = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        view.invalidate();
    }

    /** Where the bar goes (issue #91): Customization's position -- the left edge, above the speed limit sign, or the right edge. */
    static final String LEFT = "left", ABOVE_SPEED_LIMIT = "speedometer", RIGHT = "right";
    private static int speedometerId;

    private static FrameLayout.LayoutParams place(Activity a, ViewGroup decor) {
        float d = a.getResources().getDisplayMetrics().density;
        int width = Math.round(64 * d), margin = Math.round(8 * d);
        int height = Math.round(Math.min(decor.getHeight() > 0 ? decor.getHeight() * 0.42f : 420 * d, 360 * d));
        String where = Customization.string(Customization.ZONE_POSITION, LEFT);
        if (ABOVE_SPEED_LIMIT.equals(where)) {
            // Waze's speedometer (layout speedometer.xml, id Speedometer) carries the limit sign at its top right.
            if (speedometerId == 0) speedometerId = a.getResources().getIdentifier("Speedometer", "id", a.getPackageName());
            View speedometer = speedometerId != 0 ? decor.findViewById(speedometerId) : null;
            if (speedometer != null && speedometer.isShown() && speedometer.getWidth() > 0) {
                int[] at = new int[2], origin = new int[2];
                speedometer.getLocationInWindow(at);
                decor.getLocationInWindow(origin);
                int top = at[1] - origin[1], room = top - margin - Math.round(48 * d);
                if (room >= Math.round(150 * d)) {
                    int h = Math.min(height, room);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, h, Gravity.TOP | Gravity.LEFT);
                    lp.leftMargin = Math.max(margin, at[0] - origin[0] + (speedometer.getWidth() - width) / 2);
                    lp.topMargin = top - margin - h;
                    return lp;
                }
            }
            // No speedometer on screen, or no room above it: the left edge.
        }
        if (RIGHT.equals(where)) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height, Gravity.END | Gravity.CENTER_VERTICAL);
            lp.rightMargin = margin;
            return lp;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.leftMargin = margin;
        return lp;
    }

    /** Customization's position was changed: move the bar now. */
    static void moved() {
        MAIN.post(() -> {
            if (view != null) show();
        });
    }

    private static void hide() {
        if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        view = null;
    }

    /** The bar: the average in a pill at the top, the zone below it filling from the bottom, the distance left under it. */
    private static final class ZoneView extends View {
        boolean night;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;

        ZoneView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setElevation(4 * d);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            int text = night ? 0xFFE8EAED : 0xFF202124, sub = night ? 0xFF9AA0A6 : 0xFF5F6368;
            int pill = night ? 0xEE202124 : 0xF2FFFFFF, track = night ? 0x66FFFFFF : 0x33000000;
            int edge = night ? 0x33FFFFFF : 0x29000000;
            float avg = average();
            int fill = colour(avg);

            // The average, in a pill.
            float pillH = 52 * d;
            r.set(0, 0, w, pillH);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(pill);
            c.drawRoundRect(r, 14 * d, 14 * d, paint);
            outline(c, 14 * d, edge);
            paint.setColor(fill);
            r.set(0, pillH - 5 * d, w, pillH);
            c.save();
            c.clipRect(r);
            r.set(0, 0, w, pillH);
            c.drawRoundRect(r, 14 * d, 14 * d, paint);
            c.restore();
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20, getResources().getDisplayMetrics()));
            paint.setColor(text);
            c.drawText(Float.isNaN(avg) ? "–" : String.valueOf(Math.round(avg)), w / 2, 25 * d, paint);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, getResources().getDisplayMetrics()));
            paint.setColor(sub);
            c.drawText("avg " + (mph ? "mph" : "km/h"), w / 2, 40 * d, paint);

            // The zone: a track filling from the bottom.
            float distH = 26 * d, top = pillH + 8 * d, bottom = h - distH - 6 * d;
            float tw = 14 * d, left = (w - tw) / 2;
            r.set(left, top, left + tw, bottom);
            paint.setColor(track);
            c.drawRoundRect(r, tw / 2, tw / 2, paint);
            float filled = bottom - (bottom - top) * percent / 100f;
            r.set(left, Math.min(filled, bottom - tw), left + tw, bottom);
            paint.setColor(fill);
            c.drawRoundRect(r, tw / 2, tw / 2, paint);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(w / 2, Math.max(top + tw / 2, Math.min(filled, bottom - tw / 2)), tw * 0.32f, paint);

            // What is left of it.
            r.set(0, h - distH, w, h);
            paint.setColor(pill);
            c.drawRoundRect(r, distH / 2, distH / 2, paint);
            outline(c, distH / 2, edge);
            paint.setColor(text);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, getResources().getDisplayMetrics()));
            c.drawText(distance(ZoneBar.left), w / 2, h - distH / 2 + 4 * d, paint);
        }

        /** A hairline round [r], so the pills show on a light screen too. */
        private void outline(Canvas c, float radius, int colour) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, d * 0.75f));
            paint.setColor(colour);
            r.inset(0.5f * d, 0.5f * d);
            c.drawRoundRect(r, radius, radius, paint);
            r.inset(-0.5f * d, -0.5f * d);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    // ---- on the car screen (Android Auto, issue #99) -----------------------------------------------

    /*
     * Waze draws its Android Auto map itself, into the car's surface, and the only thing Java hands that surface is one
     * picture: the pill at the bottom (current street, traffic, Waze's own zone countdown). Waze builds that picture in one
     * method from the pill's state -- Context, night, pill -- every time the pill changes, which in a zone is every time
     * the distance left changes. The patch calls carPillStart() as it starts and carPill() with what it returns: while
     * the zone bar is on and a zone is under way, the picture becomes the bar laid flat. Waze places it, so the position
     * option does not apply there.
     */

    private static volatile Context carContext;
    private static volatile boolean carNight;

    /** The start of Waze's car pill method: its Context (the car screen's) and whether it draws for night. */
    public static void carPillStart(Object context, boolean night) {
        if (context instanceof Context) carContext = (Context) context;
        carNight = night;
    }

    /** What Waze's car pill method returns: a holder of one Bitmap. In a zone, the same kind of holder with the bar. */
    public static Object carPill(Object holder) {
        try {
            if (holder == null || !inZone || !Customization.on("zoneBar")) return holder;
            Context c = carContext;
            if (c == null) return holder;
            Bitmap bar = carBitmap(c, carNight);
            Object out = holder.getClass().getConstructor(Bitmap.class).newInstance(bar);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "zone bar: car pill " + bar.getWidth() + "x" + bar.getHeight() + " instead of " + holder.getClass().getName());
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "zone bar: car pill", t);
            return holder;
        }
    }

    /**
     * The bar laid flat, in Waze's pill size (current_street_pill_aaos_width, 200 dp): the average and its unit on the
     * left, the distance left on the right, the zone filling from the left under them, coloured against the limit.
     * Made the way Waze makes its own pill pictures: 12 dp of clear margin, sides rounded up to fours, unpremultiplied.
     */
    static Bitmap carBitmap(Context c, boolean night) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        float d = m.density;
        int widthId = c.getResources().getIdentifier("current_street_pill_aaos_width", "dimen", c.getPackageName());
        int w = widthId != 0 ? c.getResources().getDimensionPixelSize(widthId) : Math.round(200 * d);
        int h = Math.round(60 * d), pad = Math.round(12 * d);
        int bw = (w + 2 * pad + 3) & -4, bh = (h + 2 * pad + 3) & -4;
        Bitmap drawn = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(drawn);
        canvas.translate((bw - w) / 2f, (bh - h) / 2f);

        int text = night ? 0xFFE8EAED : 0xFF202124, sub = night ? 0xFF9AA0A6 : 0xFF5F6368;
        int pill = night ? 0xFF202124 : 0xFFFFFFFF, track = night ? 0x66FFFFFF : 0x33000000;
        float avg = average();
        int fill = colour(avg);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF(0, 0, w, h);
        paint.setColor(0x33000000);
        canvas.drawRoundRect(new RectF(0, 1.5f * d, w, h + 1.5f * d), h / 2f, h / 2f, paint);
        paint.setColor(pill);
        canvas.drawRoundRect(r, h / 2f, h / 2f, paint);

        float side = 20 * d;
        // The average, big, and its unit after it.
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22, m));
        paint.setColor(text);
        String number = Float.isNaN(avg) ? "–" : String.valueOf(Math.round(avg));
        float baseline = 30 * d;
        canvas.drawText(number, side, baseline, paint);
        float after = side + paint.measureText(number) + 5 * d;
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, m));
        paint.setColor(sub);
        canvas.drawText("avg " + (mph ? "mph" : "km/h"), after, baseline, paint);
        // What is left of the zone.
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, m));
        paint.setColor(text);
        canvas.drawText(distance(left), w - side, baseline, paint);
        // The zone: a track filling from the left.
        float th = 10 * d, top = 40 * d;
        r.set(side, top, w - side, top + th);
        paint.setColor(track);
        canvas.drawRoundRect(r, th / 2, th / 2, paint);
        float filled = side + (w - 2 * side) * percent / 100f;
        r.set(side, top, Math.max(filled, side + th), top + th);
        paint.setColor(fill);
        canvas.drawRoundRect(r, th / 2, th / 2, paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(Math.min(Math.max(filled, side + th / 2), w - side - th / 2), top + th / 2, th * 0.32f, paint);

        int[] pixels = new int[bw * bh];
        drawn.getPixels(pixels, 0, bw, 0, 0, bw, bh);
        Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        out.setPremultiplied(false);
        out.setPixels(pixels, 0, bw, 0, 0, bw, bh);
        drawn.recycle();
        return out;
    }

    // ---- reading Waze's protos --------------------------------------------------------------------

    private static Object call(Object o, String name) throws Exception {
        Method m = o.getClass().getMethod(name);
        return m.invoke(o);
    }

    // ---- the self-test (debug only) ---------------------------------------------------------------

    /**
     * `am broadcast -a org.ungoogled.waze.ZONE_TEST` with String path (insights, the default; legacy; or real --
     * through NavigationInfoNativeManager.onAverageSpeedCamZoneUpdate itself, the patched method), int length
     * (m, 3000), limit (100), String units (km/h), String averages ("95,103,110": the average to drive in each
     * quarter of the zone), int hold (seconds per quarter, 6), boolean enable (switch the bar on first); or boolean
     * customization alone, which opens the Customization screen (the side menu is out of reach before sign-up). Each
     * quarter's updates come every half second with a made-up clock, so the averages are reached exactly; then
     * the zone ends. boolean car also sends each update through carPill() and saves four car pills to files/;
     * boolean speedometer places a stand-in for Waze's speedometer (for the Above the speed limit position). Waze's real updates come the same way, through the same methods.
     */
    private static final class SelfTest extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (intent.getBooleanExtra("customization", false)) {
                    Activity a = Customization.activity();
                    if (a != null) Customization.show(a);
                    return;
                }
                if (intent.getBooleanExtra("enable", false)) Customization.setForTest("zoneBar", true);
                if (intent.getBooleanExtra("speedometer", false)) standIn();
                String path = intent.getStringExtra("path");
                if (path == null) path = "insights";
                int len = intent.getIntExtra("length", 3000), lim = intent.getIntExtra("limit", 100);
                String units = intent.getStringExtra("units");
                if (units == null) units = "km/h";
                String list = intent.getStringExtra("averages");
                if (list == null) list = "95,103,110";
                String[] parts = list.split(",");
                int[] averages = new int[parts.length];
                for (int i = 0; i < parts.length; i++) averages[i] = Integer.parseInt(parts[i].trim());
                int hold = intent.getIntExtra("hold", 6);
                car = intent.getBooleanExtra("car", false);
                carShots = 0;
                carCalls = 0;
                speedometer("State(speed=" + averages[0] + ", units=" + units + ", isUsStyle=false, maxSpeedRoad=" + lim
                        + ", enableSpeedometer=true, enableSpeedLimit=true, showSpeedLimit=true, overSpeedWithOffset=false)");
                Log.i(TAG, "zone bar self-test: " + path + ", " + len + " m, limit " + lim + " " + units + ", averages " + list);
                run(path, len, averages, hold, units.contains("mph"));
            } catch (Throwable t) {
                Log.w(TAG, "zone bar self-test", t);
            }
        }

        /** Quarter k ends at (k+1)/(n+1) of the zone, reached after driving that far at averages[k]. */
        /**
         * While a test runs, Waze's own updates are left out (signed out, Waze still sends an insights update without a
         * zone about once a second, which would end the made-up zone); only the test's own reach the bar.
         */
        static volatile boolean running;
        static boolean injecting;

        private static void run(String path, int len, int[] averages, int hold, boolean milesPerHour) {
            running = true;
            final long base = SystemClock.elapsedRealtime();
            int steps = hold * 2, n = averages.length;
            float prevDriven = 0;
            long at = 0;
            for (int k = 0; k < n; k++) {
                float driven = len * (k + 1f) / (n + 1f);
                float ms = averages[k] / (milesPerHour ? 2.2369363f : 3.6f);
                for (int s = 1; s <= steps; s++) {
                    float dk = prevDriven + (driven - prevDriven) * s / steps;
                    long virtual = base + Math.round(dk / ms * 1000);
                    int remaining = Math.round(len - dk), p = Math.round(100f * dk / len);
                    long delay = (k * steps + s) * 500L;
                    MAIN.postDelayed(() -> inject(path, p, remaining, len, virtual), delay);
                    at = delay;
                }
                prevDriven = driven;
            }
            MAIN.postDelayed(() -> {
                virtualNow = -1;
                injecting = true;
                if ("legacy".equals(path)) legacyClear();
                else if ("real".equals(path)) callReal("onAverageSpeedCamZoneClear", null);
                else insights(insightsUpdate(-1, len));
                injecting = false;
                // The real path's clear reaches the bar through the main thread (5.26): let it land first.
                MAIN.post(() -> running = false);
                Log.i(TAG, "zone bar self-test: done");
                if (standIn != null && standIn.getParent() instanceof ViewGroup) ((ViewGroup) standIn.getParent()).removeView(standIn);
                standIn = null;
            }, at + 1500L);
        }

        /**
         * With boolean speedometer: a grey square with the id of Waze's speedometer at its place on the map, bottom left,
         * for the "Above the speed limit" position on screens without the map (signed out); gone when the test ends.
         */
        private static View standIn;

        private static void standIn() {
            Activity a = Customization.activity();
            if (a == null) return;
            int id = a.getResources().getIdentifier("Speedometer", "id", a.getPackageName());
            if (id == 0) return;
            float d = a.getResources().getDisplayMetrics().density;
            standIn = new View(a);
            standIn.setId(id);
            standIn.setBackgroundColor(0xFF9E9E9E);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(110 * d), Math.round(110 * d), Gravity.BOTTOM | Gravity.START);
            lp.leftMargin = Math.round(12 * d);
            lp.bottomMargin = Math.round(140 * d);
            ((ViewGroup) a.getWindow().getDecorView()).addView(standIn, lp);
            Log.i(TAG, "zone bar self-test: speedometer stand-in placed");
        }

        /** With boolean car: each update also goes through carPill(), as Waze's car pill would, and four are saved as PNGs. */
        private static boolean car;
        private static int carShots, carCalls;

        /** A stand-in for Waze's pill holders, which hold one Bitmap. */
        public static final class Holder {
            final Bitmap bitmap;

            public Holder(Bitmap bitmap) {
                this.bitmap = bitmap;
            }
        }

        private static void carTest(int remaining) {
            Context c = Customization.appContext();
            if (c == null) return;
            carPillStart(c, carShots % 2 == 1);
            Object out = carPill(new Holder(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)));
            Bitmap b = out instanceof Holder ? ((Holder) out).bitmap : null;
            Log.i(TAG, "zone bar self-test: car pill " + (b == null ? "none" : b.getWidth() + "x" + b.getHeight()) + " at " + remaining + " m");
            if (b == null || b.getWidth() <= 4 || ++carCalls % 6 != 0 || carShots >= 4) return;
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(c.getFilesDir(), "zone_car_" + carShots + ".png"))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, f);
                carShots++;
            } catch (Throwable t) {
                Log.w(TAG, "zone bar self-test: car pill png", t);
            }
        }

        private static void inject(String path, int p, int remaining, int len, long virtual) {
            virtualNow = virtual;
            injecting = true;
            try {
                if ("insights".equals(path)) {
                    insights(insightsUpdate(remaining, len));
                } else {
                    Class<?> cls = Class.forName("com.waze.jni.protos.navigate.AverageSpeedCameraZoneUpdate");
                    Object b = cls.getMethod("newBuilder").invoke(null);
                    b.getClass().getMethod("setPercent", int.class).invoke(b, p);
                    b.getClass().getMethod("setLeftDistance", int.class).invoke(b, remaining);
                    Object update = b.getClass().getMethod("build").invoke(b);
                    if ("real".equals(path)) callReal("onAverageSpeedCamZoneUpdate", update);
                    else legacy(update);
                }
                if (car) carTest(remaining);
            } catch (Throwable t) {
                Log.w(TAG, "zone bar self-test: inject", t);
            } finally {
                virtualNow = -1;
                injecting = false;
            }
        }

        /** A SegmentInsightUpdate with an AVERAGE_SPEED_CAMERA segment WITHIN, [remaining] m left; none when negative. */
        private static Object insightsUpdate(int remaining, int len) {
            try {
                Class<?> upd = Class.forName("com.waze.jni.protos.insights_infra.SegmentInsightUpdate");
                Class<?> info = Class.forName("com.waze.jni.protos.insights_infra.SegmentInsightInfo");
                Class<?> idc = Class.forName("com.waze.jni.protos.insights_infra.SegmentInsightInfo$Id");
                Object ub = upd.getMethod("newBuilder").invoke(null);
                if (remaining >= 0) {
                    Object idb = idc.getMethod("newBuilder").invoke(null);
                    idb.getClass().getMethod("setSegmentTypeValue", int.class).invoke(idb, AVERAGE_SPEED_CAMERA);
                    idb.getClass().getMethod("setSegmentId", String.class).invoke(idb, "self-test");
                    Object ib = info.getMethod("newBuilder").invoke(null);
                    ib.getClass().getMethod("setId", idc).invoke(ib, idb.getClass().getMethod("build").invoke(idb));
                    ib.getClass().getMethod("setPositionRelativeToSegmentValue", int.class).invoke(ib, WITHIN);
                    ib.getClass().getMethod("setRemainingDistanceMeters", int.class).invoke(ib, remaining);
                    ub.getClass().getMethod("addInfo", info).invoke(ub, ib.getClass().getMethod("build").invoke(ib));
                }
                return ub.getClass().getMethod("build").invoke(ub);
            } catch (Throwable t) {
                Log.w(TAG, "zone bar self-test: insights update", t);
                return null;
            }
        }

        /** NavigationInfoNativeManager's own method, the one the patch calls into: the hook is part of the test. */
        private static void callReal(String name, Object update) {
            try {
                Class<?> nav = Class.forName("com.waze.navigate.NavigationInfoNativeManager");
                // Its singleton: getInstance() in 5.24, renamed in 5.26 -- the static no-argument method returning it.
                Object instance = null;
                for (Method x : nav.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isStatic(x.getModifiers()) && x.getParameterTypes().length == 0 && x.getReturnType() == nav) {
                        x.setAccessible(true);
                        instance = x.invoke(null);
                        break;
                    }
                }
                if (instance == null) throw new NoSuchMethodException("NavigationInfoNativeManager singleton");
                // The patched methods: the one taking the update (renamed in 5.26), and the clear by its name's start
                // (onAverageSpeedCamZoneClearJNI in 5.26, in the manager's superclass).
                Method m = null;
                for (Class<?> c = nav; c != null && m == null; c = c.getSuperclass()) {
                    for (Method x : c.getDeclaredMethods()) {
                        Class<?>[] p = x.getParameterTypes();
                        if (update != null ? p.length == 1 && p[0] == update.getClass() && x.getReturnType() == void.class
                                : p.length == 0 && x.getName().startsWith(name)) {
                            m = x;
                            break;
                        }
                    }
                }
                if (m == null) throw new NoSuchMethodException(name);
                m.setAccessible(true);
                if (update == null) m.invoke(instance);
                else m.invoke(instance, update);
            } catch (Throwable t) {
                Log.w(TAG, "zone bar self-test: " + name, t);
            }
        }
    }
}
