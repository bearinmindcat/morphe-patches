package org.ungoogled.waze;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
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

    // Main thread only.
    private static boolean inZone;
    private static long startAt, lastAt;
    private static int startLeft, length, left, percent;
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
        try {
            if (update == null || !Customization.on("zoneBar")) return;
            int p = (Integer) call(update, "getPercent"), l = (Integer) call(update, "getLeftDistance");
            long now = now();
            if (!inZone) start(l, p > 0 && p < 100 ? Math.round(l / (1f - p / 100f)) : l, now);
            progress(Math.max(0, Math.min(100, p)), l, now);
        } catch (Throwable t) {
            Log.w(TAG, "zone bar: legacy", t);
        }
    }

    /** NavigationInfoNativeManager.onAverageSpeedCamZoneClear. Main thread. */
    public static void legacyClear() {
        end("cleared");
    }

    /** InsightsInfraNativeManager.onSegmentInsightUpdated: Waze's SegmentInsightUpdate. Main thread. */
    public static void insights(Object update) {
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
                if (inZone) end("no zone in Waze's update");
                return;
            }
            long now = now();
            if (!inZone) start(remaining, remaining, now);
            int p = length > 0 ? Math.round(100f * (length - remaining) / length) : 0;
            progress(Math.max(0, Math.min(100, p)), remaining, now);
        } catch (Throwable t) {
            Log.w(TAG, "zone bar: insights", t);
        }
    }

    /** The speedometer's state as it draws: its toString() has the road's limit and the units. */
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
            float d = a.getResources().getDisplayMetrics().density;
            int height = Math.round(Math.min(decor.getHeight() > 0 ? decor.getHeight() * 0.42f : 420 * d, 360 * d));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(64 * d), height, Gravity.START | Gravity.CENTER_VERTICAL);
            lp.leftMargin = Math.round(8 * d);
            decor.addView(view, lp);
        }
        view.night = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        view.invalidate();
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
     * the zone ends. Waze's real updates come the same way, through the same methods.
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
                speedometer("State(speed=" + averages[0] + ", units=" + units + ", isUsStyle=false, maxSpeedRoad=" + lim
                        + ", enableSpeedometer=true, enableSpeedLimit=true, showSpeedLimit=true, overSpeedWithOffset=false)");
                Log.i(TAG, "zone bar self-test: " + path + ", " + len + " m, limit " + lim + " " + units + ", averages " + list);
                run(path, len, averages, hold, units.contains("mph"));
            } catch (Throwable t) {
                Log.w(TAG, "zone bar self-test", t);
            }
        }

        /** Quarter k ends at (k+1)/(n+1) of the zone, reached after driving that far at averages[k]. */
        private static void run(String path, int len, int[] averages, int hold, boolean milesPerHour) {
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
                if ("legacy".equals(path)) legacyClear();
                else if ("real".equals(path)) callReal("onAverageSpeedCamZoneClear", null);
                else insights(insightsUpdate(-1, len));
                Log.i(TAG, "zone bar self-test: done");
            }, at + 1500L);
        }

        private static void inject(String path, int p, int remaining, int len, long virtual) {
            virtualNow = virtual;
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
            } catch (Throwable t) {
                Log.w(TAG, "zone bar self-test: inject", t);
            } finally {
                virtualNow = -1;
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
                Object instance = nav.getMethod("getInstance").invoke(null);
                Method m = update == null ? nav.getDeclaredMethod(name) : nav.getDeclaredMethod(name, update.getClass());
                m.setAccessible(true);
                if (update == null) m.invoke(instance);
                else m.invoke(instance, update);
            } catch (Throwable t) {
                Log.w(TAG, "zone bar self-test: " + name, t);
            }
        }
    }
}
