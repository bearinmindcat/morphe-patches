package org.ungoogled.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * The nearest Waze alert or camera ahead, as a line on the power saving screen (issue #207). That screen's map is
 * roads only, so no pin can show there; the alerts' own location checks report what is ahead at every fix, and the
 * screen shows it with its distance, e.g. "👮 Police reported · 450 m", until it is passed.
 */
final class Ahead {
    private Ahead() {}

    static final int WAZE = 0, CAMERAS = 1;
    /** A report older than this is not shown: the alerts stopped looking, or the car stopped. */
    private static final long FRESH_MS = 10_000L;

    private static final String[] emoji = new String[2], label = new String[2];
    private static final double[] meters = new double[2];
    private static final long[] at = new long[2];

    /** [source] sees [what] ahead at [distance] metres. */
    static synchronized void report(int source, String icon, String what, double distance) {
        emoji[source] = icon;
        label[source] = what;
        meters[source] = distance;
        at[source] = SystemClock.elapsedRealtime();
        Badge.refresh();
    }

    /** [source] sees nothing ahead. */
    static synchronized void none(int source) {
        if (at[source] == 0) return;
        at[source] = 0;
        Badge.refresh();
    }

    /** The line to show, or null: the nearer of the fresh reports. */
    static synchronized String text() {
        long now = SystemClock.elapsedRealtime();
        int pick = -1;
        for (int s = 0; s < 2; s++) {
            if (at[s] == 0 || now - at[s] > FRESH_MS) continue;
            if (pick < 0 || meters[s] < meters[pick]) pick = s;
        }
        if (pick < 0) return null;
        return emoji[pick] + " " + label[pick] + " · " + distance(meters[pick], PowerSaving.miles());
    }

    /** "450 m", "1.2 km"; "300 ft", "0.4 mi". */
    static String distance(double m, boolean miles) {
        if (miles) {
            double ft = m * 3.28084;
            if (ft < 528) return Math.max(50, Math.round(ft / 50) * 50) + " ft";
            return String.format(java.util.Locale.US, "%.1f mi", m / 1609.344);
        }
        if (m < 1000) return Math.max(10, Math.round(m / 10) * 10) + " m";
        return String.format(java.util.Locale.US, "%.1f km", m / 1000);
    }

    private static boolean tracking;

    /** Once, from the alerts' settings refresh: puts the badge on the power saving screen while it is started. */
    static void track(Context c) {
        Context app = c.getApplicationContext();
        if (tracking || !(app instanceof Application)) return;
        tracking = true;
        ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { if (PowerSaving.MIN_MODE.equals(a.getClass().getName())) Badge.start(a); }
            @Override public void onActivityStopped(Activity a) { if (PowerSaving.MIN_MODE.equals(a.getClass().getName())) Badge.stop(); }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    /**
     * White on black like the rest of the power saving screen, bottom left above where the speedometer goes, inside its
     * BurnInLayout so it moves with everything else; under the screen's tap catcher, so a tap anywhere still goes back.
     */
    static final class Badge extends TextView {
        private static WeakReference<Badge> shown = new WeakReference<>(null);
        private String last;

        Badge(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF000000);
            bg.setStroke(Math.round(1.5f * d), 0xFF5F6368);
            bg.setCornerRadius(18 * d);
            setBackground(bg);
            setTextColor(0xFFFFFFFF);
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
            setSingleLine(true);
            setEllipsize(android.text.TextUtils.TruncateAt.END);
            setMaxWidth(Math.round(c.getResources().getDisplayMetrics().widthPixels * 0.75f));
            setPadding(Math.round(14 * d), Math.round(8 * d), Math.round(14 * d), Math.round(8 * d));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(GONE);
        }

        static void start(Activity a) {
            if (shown.get() != null) return;
            try {
                ViewGroup host = PowerSaving.Speedometer.burnInLayout(a.getWindow().getDecorView());
                if (host == null) host = a.findViewById(android.R.id.content);
                if (host == null) return;
                Badge b = new Badge(a);
                float d = a.getResources().getDisplayMetrics().density;
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM | android.view.Gravity.START);
                lp.leftMargin = Math.round(16 * d);
                lp.bottomMargin = Math.round((PowerSaving.SPEEDOMETER ? 112 + 64 + 12 : 112) * d);
                host.addView(b, Math.max(0, host.getChildCount() - 1), lp);
                shown = new WeakReference<>(b);
                b.update();
            } catch (Throwable t) {
                android.util.Log.w("UA", "power saving: alert line", t);
            }
        }

        static void stop() {
            Badge b = shown.get();
            shown = new WeakReference<>(null);
            if (b == null) return;
            b.removeCallbacks(b.tick);
            if (b.getParent() instanceof ViewGroup) ((ViewGroup) b.getParent()).removeView(b);
        }

        static void refresh() {
            Badge b = shown.get();
            if (b != null) b.post(b::update);
        }

        /** Once a second too, so a report that stops coming in goes away. */
        private final Runnable tick = this::update;

        private void update() {
            removeCallbacks(tick);
            if (shown.get() != this) return;
            String t = text();
            if (t == null) {
                setVisibility(GONE);
            } else {
                setText(t);
                setVisibility(VISIBLE);
            }
            String what = t == null ? null : t.substring(0, t.lastIndexOf(" · "));
            if (what == null ? last != null : !what.equals(last)) {
                last = what;
                android.util.Log.i("UA", "power saving: " + (t == null ? "nothing ahead" : "showing " + t));
            }
            postDelayed(tick, 1000);
        }
    }
}
