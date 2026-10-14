package org.ungoogled.ui;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Waze's alerts on Maps' own map -- police, crashes, hazards, traffic jams, closures, speed cameras --
 * as pins, with a chime and a note as one comes up ahead, from a Waze running on the same phone.
 *
 * Waze keeps a list of the alerts it is about to warn about, around the car and along its route.
 * Patched with Share alerts with Maps it broadcasts that list on the phone (org.ungoogled.waze.ALERTS)
 * each time it changes and once a minute besides; this receives it, and asks for it
 * (org.ungoogled.waze.ALERTS_REQUEST) when switched on, as Maps starts and as it comes back on screen,
 * so the pins need not wait for Waze's next minute. Off until switched on in Waze
 * Features. Waze has to be signed in and open -- in the background is enough -- with a GPS fix of its
 * own. Which kinds show is chosen in Waze alert types, police alone to begin with; the list is dropped
 * three minutes after the last word from Waze. An alert is "ahead" by the speed cameras' test: on the
 * line you are driving along, within the distance Waze itself would warn at.
 */
public final class WazeAlerts {
    private WazeAlerts() {}

    /** The broadcast Waze's bridge sends; its extras are described in the Waze extension (Bridge). */
    public static final String ACTION = "org.ungoogled.waze.ALERTS";
    /** To Waze's bridge: send the list now. */
    static final String ACTION_REQUEST = "org.ungoogled.waze.ALERTS_REQUEST";
    /** Back on screen with nothing from Waze for this long: ask again. */
    private static final long ASK_AFTER_MS = 30_000L;
    public static final String KEY_ON = "waze_alerts";
    public static final String KEY_MAP = "waze_alerts_map";
    public static final String KEY_CHIME = "waze_alerts_chime";
    public static final String KEY_KINDS = "waze_alerts_kinds";

    /** The kinds Waze alert types offers; Waze's own alert types (AlerterType numbers) are grouped into them. */
    static final int POLICE = 0, CRASH = 1, HAZARD = 2, JAM = 3, CLOSURE = 4, CAMERA = 5, OTHER = 6;
    static final String[] KIND_LABELS = {
            "Police", "Crashes", "Hazards", "Traffic jams", "Road closures and construction", "Speed cameras", "Everything else",
    };
    private static final String[] KIND_NAMES = {"Police", "Crash", "Hazard", "Traffic jam", "Road closure", "Speed camera", "Waze alert"};
    private static final String[] KIND_EMOJI = {
            "👮",         // police officer
            "💥",         // collision
            "⚠️",         // warning sign
            "🚗",         // car
            "🚧",         // construction
            "📷",         // camera
            "❗",               // exclamation mark
    };
    static final int DEFAULT_KINDS = 1 << POLICE;
    /** Without a word from Waze for this long the alerts are dropped: Waze was closed. */
    private static final long STALE_MS = 3 * 60 * 1000L;

    /** Receiving at all; the alerts as pins; the chime for one ahead. */
    static volatile boolean ON, MAP, CHIME;
    static volatile int KINDS = DEFAULT_KINDS;

    static final class Alert {
        final String id, title, description;
        final int type, kind, trigger;
        final double lat, lng;

        Alert(String id, int type, double lat, double lng, int trigger, String title, String description) {
            this.id = id; this.type = type; this.kind = kind(type); this.lat = lat; this.lng = lng;
            this.trigger = trigger; this.title = title; this.description = description;
        }
    }

    private static volatile List<Alert> alerts = Collections.emptyList();
    private static volatile long receivedAt;
    private static volatile int receivedCount;
    private static final Map<String, Long> alerted = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Location last;
    private static Location previous;
    private static boolean navigating, listening, tracking, registered;
    private static Context host;
    private static int resumed;

    /** Waze's AlerterType number to one of the kinds above. */
    static int kind(int type) {
        switch (type) {
            case 1: return POLICE;
            case 2: return CRASH;
            case 5: case 19: case 20: case 21: case 26: return HAZARD;   // hazard, blocked lane, bad weather, permanent hazard, animal
            case 3: case 4: case 32: return JAM;                        // jam, traffic info, sudden slowdown
            case 7: case 12: return CLOSURE;                            // construction, closure
            case 10: return CAMERA;
            default: return OTHER;
        }
    }

    // ---- settings ----------------------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return Shapes.wazeAlertsPatched() && prefs(c).getBoolean(KEY_ON, false);
    }

    public static boolean onMap(Context c) {
        return prefs(c).getBoolean(KEY_MAP, true);
    }

    public static boolean chime(Context c) {
        return prefs(c).getBoolean(KEY_CHIME, true);
    }

    public static int kinds(Context c) {
        return prefs(c).getInt(KEY_KINDS, DEFAULT_KINDS);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).commit();
        refresh(c);
        SavedOnMap.refresh();
    }

    public static void setOnMap(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_MAP, on).commit();
        refresh(c);
        SavedOnMap.refresh();
    }

    public static void setChime(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_CHIME, on).commit();
        refresh(c);
    }

    public static void setKinds(Context c, int mask) {
        prefs(c).edit().putInt(KEY_KINDS, mask).commit();
        refresh(c);
        SavedOnMap.refresh();
    }

    /** "Police, Crashes": the kinds ticked, or "None". */
    static String kindsSummary(Context c) {
        int mask = kinds(c);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < KIND_LABELS.length; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (out.length() > 0) out.append(", ");
            out.append(KIND_LABELS[i]);
        }
        return out.length() == 0 ? "None" : out.toString();
    }

    /** At every Activity attach and after a change in Waze & external data source: re-reads the settings. */
    static void refresh(Context c) {
        ON = enabled(c);
        MAP = ON && onMap(c);
        CHIME = ON && chime(c);
        KINDS = kinds(c);
        Context app = c.getApplicationContext();
        if (!tracking && app instanceof Application) {
            tracking = true;
            ((Application) app).registerActivityLifecycleCallbacks(new Lifecycle());
        }
        MAIN.post(() -> {
            receiver(app);
            listen();
        });
    }

    /** From the navigation camera hook: about once a second while guidance runs, false when it ends. */
    static void navigating(boolean on) {
        if (navigating == on) return;
        MAIN.post(() -> {
            navigating = on;
            listen();
        });
    }

    // ---- the list from Waze ------------------------------------------------------------

    /** Main thread: registered while switched on, on the Application, so it outlives any one screen. */
    private static void receiver(Context app) {
        if (ON == registered) return;
        try {
            if (ON) {
                IntentFilter f = new IntentFilter(ACTION);
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(RECEIVER, f, Context.RECEIVER_EXPORTED);
                else app.registerReceiver(RECEIVER, f);
                host = app;
                registered = true;
                ask();
            } else {
                if (host != null) host.unregisterReceiver(RECEIVER);
                host = null;
                receivedAt = 0;
                clear("switched off");
            }
            registered = ON;
        } catch (Throwable t) {
            android.util.Log.w("UA", "waze alerts: receiver", t);
        }
    }

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent in) {
            try {
                received(in);
            } catch (Throwable t) {
                android.util.Log.w("UA", "waze alerts", t);
            }
        }
    };

    /** Main thread: the whole list Waze is tracking now, in place of the last one. */
    private static void received(Intent in) {
        if (!ON) return;
        int[] type = in.getIntArrayExtra("type"), lat = in.getIntArrayExtra("lat"), lng = in.getIntArrayExtra("lng");
        int[] trigger = in.getIntArrayExtra("trigger");
        String[] id = in.getStringArrayExtra("id"), title = in.getStringArrayExtra("title");
        String[] description = in.getStringArrayExtra("description");
        if (type == null || lat == null || lng == null || lat.length != type.length || lng.length != type.length) return;
        List<Alert> out = new ArrayList<>(type.length);
        for (int i = 0; i < type.length; i++) {
            if (lat[i] == 0 && lng[i] == 0) continue;
            out.add(new Alert(at(id, i, lat[i] + "," + lng[i]), type[i], lat[i] / 1e6, lng[i] / 1e6,
                    trigger != null && i < trigger.length ? trigger[i] : 0, at(title, i, ""), at(description, i, "")));
        }
        alerts = Collections.unmodifiableList(out);
        receivedAt = SystemClock.elapsedRealtime();
        receivedCount = out.size();
        android.util.Log.i("UA", "waze alerts: " + out.size() + " from " + in.getStringExtra("from"));
        MAIN.removeCallbacks(EXPIRE);
        if (!out.isEmpty()) MAIN.postDelayed(EXPIRE, STALE_MS + 1000L);
        if (MAP) SavedOnMap.refresh();
        Location l = last;
        // With a fresh fix, one of the new alerts may already be right ahead.
        if (l != null && SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos() < 15_000_000_000L) alert(l);
    }

    /** Asks Waze's bridge for its list now; it answers only while Waze runs. Main thread. */
    static void ask() {
        Context c = host;
        if (c == null || !ON) return;
        try {
            c.sendBroadcast(new Intent(ACTION_REQUEST)
                    .putExtra("from", c.getPackageName())
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
        } catch (Throwable t) {
            android.util.Log.w("UA", "waze alerts: ask", t);
        }
    }

    /** For Waze & external data source: the last word from Waze, in a line. */
    static String status() {
        if (!ON) return "Off";
        long at = receivedAt;
        if (at == 0) return "Nothing from Waze yet. Waze sends its alerts while it is open; tap to ask again";
        int n = receivedCount, shown = 0;
        for (Alert a : alerts) if ((KINDS & (1 << a.kind)) != 0) shown++;
        String count = n == 1 ? "1 alert" : n + " alerts";
        if (n > 0 && shown != n) count += ", " + shown + " of the types chosen";
        return count + " from Waze, " + ago(SystemClock.elapsedRealtime() - at);
    }

    /** "just now", "40 seconds ago", "3 minutes ago". */
    static String ago(long ms) {
        long s = ms / 1000;
        if (s < 5) return "just now";
        if (s < 60) return s + " seconds ago";
        long m = s / 60;
        return m == 1 ? "a minute ago" : m + " minutes ago";
    }

    private static String at(String[] a, int i, String fallback) {
        return a != null && i < a.length && a[i] != null ? a[i] : fallback;
    }

    private static final Runnable EXPIRE = () -> {
        if (SystemClock.elapsedRealtime() - receivedAt >= STALE_MS) clear("nothing from Waze for 3 minutes");
    };

    private static void clear(String why) {
        if (alerts.isEmpty()) return;
        alerts = Collections.emptyList();
        android.util.Log.i("UA", "waze alerts: dropped, " + why);
        SavedOnMap.refresh();
    }

    // ---- location, for the chime -------------------------------------------------------

    private static final LocationListener GPS = new LocationListener() {
        @Override public void onLocationChanged(Location l) { located(l); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    /** GPS on while the chime is on and Maps is open or navigating; off otherwise. Main thread. */
    private static void listen() {
        Context c = Shapes.appContext();
        if (c == null) return;
        boolean want = CHIME && (resumed > 0 || navigating);
        if (want == listening) return;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            if (want) {
                if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, GPS, Looper.getMainLooper());
            } else {
                lm.removeUpdates(GPS);
                previous = null;
            }
            listening = want;
        } catch (Throwable t) {
            android.util.Log.w("UA", "waze alerts: location", t);
        }
    }

    private static void located(Location l) {
        last = l;
        try {
            alert(l);
        } catch (Throwable t) {
            android.util.Log.w("UA", "waze alerts: alert", t);
        }
    }

    /** Chimes for the nearest alert of a chosen kind on the line we are driving along, once each. */
    private static void alert(Location l) {
        if (!CHIME) return;
        List<Alert> list = alerts;
        if (list.isEmpty()) return;
        // Speed and heading as the speed cameras work them out: the fix's own while it reports moving,
        // else from the last fix 8 m or more away.
        float speed = l.hasSpeed() ? l.getSpeed() : 0f;
        boolean bearingGood = l.hasBearing() && speed >= 2f
                && (!l.hasBearingAccuracy() || l.getBearingAccuracyDegrees() <= 20f);
        float heading = bearingGood ? l.getBearing() : Float.NaN;
        if (previous == null) {
            previous = l;
        } else {
            float moved = previous.distanceTo(l);
            if (moved >= 8f) {
                long dt = l.getElapsedRealtimeNanos() - previous.getElapsedRealtimeNanos();
                if (speed < 0.5f && dt > 0) speed = moved / (dt / 1e9f);
                if (Float.isNaN(heading)) heading = previous.bearingTo(l);
                previous = l;
            }
        }
        if (speed < 3f || Float.isNaN(heading)) return;   // about 7 mph: walking, or stopped at the lights

        Alert best = null;
        double bestDistance = Double.MAX_VALUE;
        float[] result = new float[2];
        for (Alert a : list) {
            if ((KINDS & (1 << a.kind)) == 0) continue;
            Location.distanceBetween(l.getLatitude(), l.getLongitude(), a.lat, a.lng, result);
            double d = result[0];
            // Within the distance Waze itself warns at (kept to 300 m .. 1.5 km), else 20 seconds away.
            double reach = a.trigger > 0 ? Math.max(300.0, Math.min(1500.0, a.trigger)) : Math.max(300.0, Math.min(1000.0, speed * 20.0));
            if (d > reach) continue;
            double off = Math.abs(((result[1] - heading) % 360 + 540) % 360 - 180);
            if (off > 80) continue;                                  // behind, or off to the side
            if (d * Math.sin(Math.toRadians(off)) > 80) continue;    // not on the line we are driving
            if (d < bestDistance) {
                best = a;
                bestDistance = d;
            }
        }
        if (best == null) return;
        long now = SystemClock.elapsedRealtime();
        Long at = alerted.get(best.id);
        if (at != null && now - at < 10 * 60 * 1000L) return;
        if (alerted.size() > 200) alerted.values().removeIf(t -> now - t > 10 * 60 * 1000L);
        alerted.put(best.id, now);
        android.util.Log.i("UA", "waze alert ahead: " + label(best) + ", " + Math.round(bestDistance) + " m");
        Chime.alert();
        Context c = Shapes.appContext();
        if (c != null) Toast.makeText(c, KIND_EMOJI[best.kind] + " " + label(best), Toast.LENGTH_LONG).show();
    }

    /** Waze's own title ("Police reported ahead"), else the kind. */
    static String label(Alert a) {
        return a.title.isEmpty() ? KIND_NAMES[a.kind] : a.title;
    }

    // ---- the map -----------------------------------------------------------------------

    /** For SavedOnMap: the alerts of the kinds chosen, as Maps' personal places, one list a kind. */
    static List<Object> mapItems() {
        List<Object> out = new ArrayList<>();
        if (!MAP) return out;
        Map<Integer, Object> lists = new HashMap<>();
        for (Alert a : alerts) {
            if ((KINDS & (1 << a.kind)) == 0) continue;
            Object list = lists.get(a.kind);
            if (list == null && !lists.containsKey(a.kind)) {
                list = SavedOnMap.listInfo("ungoogled:waze:" + a.kind, "CUSTOM", KIND_LABELS[a.kind], KIND_EMOJI[a.kind]);
                lists.put(a.kind, list);
            }
            String name = label(a);
            Object item = SavedOnMap.item(0, 0, a.lat, a.lng, name, a.description.isEmpty() ? "From Waze" : a.description, 0, list, false);
            if (item != null) out.add(item);
        }
        return out;
    }

    // ---- Maps open or not --------------------------------------------------------------

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) {
            resumed++;
            listen();
            // Back on screen with nothing recent from Waze: ask, rather than wait for its next minute.
            if (registered && SystemClock.elapsedRealtime() - receivedAt > ASK_AFTER_MS) ask();
        }
        @Override public void onActivityPaused(Activity a) { resumed = Math.max(0, resumed - 1); listen(); }
        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }
}
