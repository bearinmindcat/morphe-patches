package org.ungoogled.xiaomiweather;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether Weather may use Xiaomi's weather server (forecasts, air quality, alerts and city search for your cities and
 * your location). On HyperOS the user accepts Xiaomi's notice in the Security app or Weather's own privacy dialog; off
 * HyperOS Weather counted that notice as accepted and went online at once. Now every request of Weather's HTTP client
 * waits for an answer here: the main screen asks once, "Don't allow" leaves Weather empty and offline, and pulling the
 * weather screen down or adding a city asks again.
 */
public final class Consent {
    private static final String PREFS = "ungoogled_weather_consent";
    private static final String KEY = "xiaomi_weather_server";
    private static final String TAG = "UngoogledWeather";
    private static final int UNDECIDED = 0, ALLOWED = 1, DECLINED = 2;

    /** Weather's add-city screen: it only asks the server when the user searches or picks a city. */
    private static final String SEARCH_SCREEN = "ActivitySearchCity";

    private static Context app;
    private static WeakReference<Activity> screen = new WeakReference<>(null);
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static AlertDialog dialog;
    private static final List<Runnable> waiting = new ArrayList<>();
    private static long lastAnswer;

    private Consent() {
    }

    /** Weather's Application: keeps the context the request check reads the answer with, and the visible screen. */
    public static void init(Context context) {
        if (context == null) return;
        boolean first = app == null;
        app = context.getApplicationContext();
        if (first && app instanceof Application) {
            ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) { resumed = new WeakReference<>(activity); }
                @Override public void onActivityPaused(Activity activity) {
                    if (resumed.get() == activity) resumed = new WeakReference<>(null);
                }
                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivityStopped(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) { }
            });
        }
    }

    private static int answer() {
        try {
            return app == null ? UNDECIDED : app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, UNDECIDED);
        } catch (RuntimeException e) {
            return UNDECIDED;
        }
    }

    private static void save(int value) {
        if (app != null) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, value).commit();
    }

    /** True once the user allowed Xiaomi's weather server. */
    public static boolean network() {
        return answer() == ALLOWED;
    }

    /** Requests the add-city screen makes because the user searched, picked a city or asked for their location. */
    private static final String[] USER_REQUESTS = {"location/city/search", "location/city/geo", "weather/all", "weather/apart"};

    /**
     * Every request of Weather's HTTP client ({@code call}: OkHttp's call): true when allowed. A refused request the
     * user caused on the add-city screen (a search, a picked city, their location) asks again, unless the question was
     * just answered; the screen's own list of popular cities does not.
     */
    public static boolean request(Object call) {
        if (network()) return true;
        String what;
        try {
            what = String.valueOf(call.getClass().getMethod("request").invoke(call));
        } catch (ReflectiveOperationException | RuntimeException e) {
            what = "";
        }
        Log.i(TAG, "Xiaomi's weather server is not allowed: " + what);
        boolean user = false;
        for (String path : USER_REQUESTS) user |= what.contains(path);
        if (!user) return false;
        new Handler(Looper.getMainLooper()).post(() -> {
            Activity activity = resumed.get();
            if (activity == null || activity.isFinishing() || !activity.getClass().getName().endsWith(SEARCH_SCREEN)) return;
            if (answer() == ALLOWED || SystemClock.elapsedRealtime() - lastAnswer < 15000) return;
            show(activity);
        });
        return false;
    }

    /** The main screen became visible: asks once, if nobody has answered yet. */
    public static void attach(Activity activity) {
        init(activity);
        screen = new WeakReference<>(activity);
        if (answer() == UNDECIDED) show(activity);
    }

    /**
     * Weather's first-run step (its own notice, then the location prompt and the first forecast): waits until the
     * question is answered. Returns true when {@code step} will run later.
     */
    public static boolean defer(Runnable step) {
        if (answer() != UNDECIDED) return false;
        Activity activity = screen.get();
        if (activity == null || activity.isFinishing()) return false;
        waiting.add(step);
        show(activity);
        return true;
    }

    /** Pull to refresh: after "Don't allow", asks again. */
    public static void offer() {
        Activity activity = screen.get();
        if (activity != null && !activity.isFinishing() && answer() == DECLINED) show(activity);
    }

    private static void show(Activity activity) {
        if (dialog != null && dialog.isShowing() && dialog.getOwnerActivity() == activity) return;
        boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int theme = night ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        try {
            dialog = new AlertDialog.Builder(activity, theme)
                    .setMessage("Xiaomi Weather gets forecasts, air quality and alerts for your cities and your location "
                            + "from Xiaomi's weather server.\n\nIf you don't allow it, Weather stays empty; you're asked "
                            + "again when you add a city or pull the weather screen down.")
                    .setCancelable(false)
                    .setPositiveButton("Allow", (d, which) -> answered(activity, ALLOWED))
                    .setNegativeButton("Don't allow", (d, which) -> answered(activity, DECLINED))
                    .create();
            dialog.setOwnerActivity(activity);
            dialog.show();
        } catch (RuntimeException e) {
            dialog = null;
        }
    }

    private static void answered(Activity activity, int value) {
        save(value);
        lastAnswer = SystemClock.elapsedRealtime();
        dialog = null;
        List<Runnable> steps = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable step : steps) step.run();
        // Allowed after Weather already tried (and failed) to load: load again.
        if (value == ALLOWED && steps.isEmpty() && !activity.isFinishing()) activity.recreate();
    }
}
