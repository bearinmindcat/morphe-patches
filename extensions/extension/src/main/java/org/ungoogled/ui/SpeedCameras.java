package org.ungoogled.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Speed and red light cameras from OpenStreetMap, the part of Waze people keep it for: a chime and
 * a note as a camera comes up ahead, and every camera as a pin on Maps' own map.
 *
 * Off until switched on in Customization, because it goes online: the cameras around you come from
 * an OpenStreetMap server (the Overpass API), in 0.25-degree squares -- about 25 km across: the square
 * you are in and, by the Camera download area setting, the ring of squares around it (none, one -- the
 * default, about 75 km across -- two or three). Each square is kept on the phone for a week,
 * so the server learns little more than which squares you pass through, and the Proxy setting covers
 * these requests too. The location comes from Android's GPS while Maps is open or navigating.
 *
 * A camera is "ahead" when it lies on the line you are driving along: within 60 m of it, in front,
 * and close enough for the speed -- 20 seconds away, at least 250 m and at most 1 km. Each camera
 * alerts once, then not again for five minutes.
 */
public final class SpeedCameras {
    private SpeedCameras() {}

    public static final String KEY_ON = "speed_cameras";
    public static final String KEY_MAP = "speed_cameras_map";
    /** Rings of squares around the one you are in: 0 = about 25 km across, 1 = about 75 km (default), 2, 3. */
    public static final String KEY_AREA = "speed_cameras_area";
    static final int AREA_DEFAULT = 1, AREA_MAX = 3;
    /** The Camera download area's choices, in ring order. */
    static final String[] AREA_LABELS = {
            "About 25 km (the square you are in)",
            "About 75 km (and the squares around it)",
            "About 125 km",
            "About 175 km",
    };

    static final int SPEED = 0, RED_LIGHT = 1, AVERAGE = 2, STOP = 3;

    /** Alerts and downloads; the cameras as pins on the map. */
    static volatile boolean ON, MAP;
    /** Rings of squares downloaded and drawn around the one you are in. */
    static volatile int RING = AREA_DEFAULT;

    private static final double TILE = 0.25;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static final String[] SERVERS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
    };
    private static final String AGENT = "Ungoogled-Maps-speed-cameras/1.0";

    static final class Camera {
        final long id;
        final double lat, lng;
        final int kind;
        final String limit;

        Camera(long id, double lat, double lng, int kind, String limit) {
            this.id = id; this.lat = lat; this.lng = lng; this.kind = kind; this.limit = limit;
        }
    }

    private static final Map<Long, List<Camera>> tiles = new ConcurrentHashMap<>();
    /** Squares loaded, or downloaded, under a week ago; others in [tiles] are older copies. */
    private static final Set<Long> fresh = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile boolean busy;
    private static volatile long retryAt;
    private static int failures;
    private static final Map<Long, Long> alerted = new HashMap<>();
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ua-cameras");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile Location last;
    private static Location previous;
    private static boolean navigating, listening, tracking;
    private static int resumed;

    // ---- settings ----------------------------------------------------------------------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Shapes.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return Shapes.speedCamerasPatched() && prefs(c).getBoolean(KEY_ON, false);
    }

    public static boolean onMap(Context c) {
        return prefs(c).getBoolean(KEY_MAP, true);
    }

    public static int area(Context c) {
        int ring = prefs(c).getInt(KEY_AREA, AREA_DEFAULT);
        return Math.max(0, Math.min(AREA_MAX, ring));
    }

    public static void setArea(Context c, int ring) {
        prefs(c).edit().putInt(KEY_AREA, Math.max(0, Math.min(AREA_MAX, ring))).commit();
        refresh(c);
        Location l = last;
        if (l != null) MAIN.post(() -> around(l.getLatitude(), l.getLongitude()));
        SavedOnMap.refresh();
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

    /** At every Activity attach and after a change in Customization: re-reads the settings. */
    static void refresh(Context c) {
        ON = enabled(c);
        MAP = ON && onMap(c);
        RING = area(c);
        Context app = c.getApplicationContext();
        if (!tracking && app instanceof Application) {
            tracking = true;
            ((Application) app).registerActivityLifecycleCallbacks(new Lifecycle());
        }
        MAIN.post(SpeedCameras::listen);
    }

    /** From the navigation camera hook: about once a second while guidance runs, false when it ends. */
    static void navigating(boolean on) {
        if (navigating == on) return;
        MAIN.post(() -> {
            navigating = on;
            listen();
        });
    }

    // ---- location ----------------------------------------------------------------------

    private static final LocationListener GPS = new LocationListener() {
        @Override public void onLocationChanged(Location l) { located(l); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    /** GPS on while switched on and Maps is open or navigating; off otherwise. Main thread. */
    private static void listen() {
        Context c = Shapes.appContext();
        if (c == null) return;
        boolean want = ON && (resumed > 0 || navigating);
        if (want == listening) return;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            if (want) {
                if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, GPS, Looper.getMainLooper());
                Location known = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (known != null) located(known);
            } else {
                lm.removeUpdates(GPS);
                previous = null;
            }
            listening = want;
        } catch (Throwable t) {
            android.util.Log.w("UA", "speed cameras: location", t);
        }
    }

    private static void located(Location l) {
        Location before = last;
        last = l;
        around(l.getLatitude(), l.getLongitude());
        if (before == null || tile(before.getLatitude(), before.getLongitude()) != tile(l.getLatitude(), l.getLongitude())) {
            if (MAP) SavedOnMap.refresh();
        }
        try {
            alert(l);
        } catch (Throwable t) {
            android.util.Log.w("UA", "speed cameras: alert", t);
        }
    }

    // ---- alerts ------------------------------------------------------------------------

    private static void alert(Location l) {
        if (!ON) return;
        // Speed and heading: the fix's own while it reports moving, else worked out from the last fix
        // 8 m or more away -- some receivers, and the emulator, report 0 for both. A bearing is used
        // only when it claims to be good to 20 degrees; the emulator's is 0 (north) give or take 30.
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
        if (speed < 4f || Float.isNaN(heading)) return;   // about 9 mph: walking, or stopped at the lights

        double reach = Math.max(250.0, Math.min(1000.0, speed * 20.0));
        Camera best = null;
        double bestDistance = Double.MAX_VALUE;
        float[] result = new float[2];
        for (Camera cam : near(l.getLatitude(), l.getLongitude(), 1)) {
            Location.distanceBetween(l.getLatitude(), l.getLongitude(), cam.lat, cam.lng, result);
            double d = result[0];
            if (d > reach) continue;
            double off = Math.abs(((result[1] - heading) % 360 + 540) % 360 - 180);
            if (off > 80) continue;                                  // behind, or off to the side
            if (d * Math.sin(Math.toRadians(off)) > 60) continue;    // not on the line we are driving
            if (d < bestDistance) {
                best = cam;
                bestDistance = d;
            }
        }
        if (best == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        Long at = alerted.get(best.id);
        if (at != null && now - at < 5 * 60 * 1000L) return;
        if (alerted.size() > 200) alerted.values().removeIf(t -> now - t > 5 * 60 * 1000L);
        // The camera, and any other mapped within 50 m of it: the same camera drawn twice, or a pair
        // at one junction, chime once.
        for (Camera cam : near(best.lat, best.lng, 1)) {
            Location.distanceBetween(best.lat, best.lng, cam.lat, cam.lng, result);
            if (result[0] <= 50) alerted.put(cam.id, now);
        }
        android.util.Log.i("UA", "speed camera ahead: " + label(best) + ", " + Math.round(bestDistance) + " m");
        Chime.camera();
        Context c = Shapes.appContext();
        if (c != null) Toast.makeText(c, emoji(best.kind) + " " + label(best) + " ahead", Toast.LENGTH_LONG).show();
    }

    /** "Speed camera · 25 mph", "Red light camera", ... */
    static String label(Camera c) {
        String what;
        switch (c.kind) {
            case RED_LIGHT: what = "Red light camera"; break;
            case AVERAGE: what = "Average speed camera"; break;
            case STOP: what = "Stop sign camera"; break;
            default: what = "Speed camera";
        }
        return c.limit == null || c.limit.isEmpty() ? what : what + " · " + c.limit;
    }

    static String emoji(int kind) {
        switch (kind) {
            case RED_LIGHT: return "\uD83D\uDEA6";   // vertical traffic light
            case STOP: return "\uD83D\uDED1";        // stop sign
            default: return "\uD83D\uDCF7";          // camera
        }
    }

    // ---- the map -----------------------------------------------------------------------

    /** For SavedOnMap: the cameras around the last location, nearest first, as Maps' personal places. */
    static List<Object> mapItems() {
        List<Object> out = new ArrayList<>();
        Location l = last;
        if (!MAP || l == null) return out;
        List<Camera> cams = new ArrayList<>(near(l.getLatitude(), l.getLongitude(), RING));
        final double lat = l.getLatitude(), lng = l.getLongitude();
        Collections.sort(cams, (a, b) -> Double.compare(sq(a, lat, lng), sq(b, lat, lng)));
        Map<Integer, Object> lists = new HashMap<>();
        for (Camera cam : cams) {
            if (out.size() >= 600) break;
            Object list = lists.get(cam.kind);
            if (list == null && !lists.containsKey(cam.kind)) {
                list = SavedOnMap.listInfo("ungoogled:cameras:" + cam.kind, "CUSTOM", label(new Camera(0, 0, 0, cam.kind, null)) + "s", emoji(cam.kind));
                lists.put(cam.kind, list);
            }
            String name = label(cam);
            Object item = SavedOnMap.item(0, 0, cam.lat, cam.lng, name, name, 0, list, false);
            if (item != null) out.add(item);
        }
        return out;
    }

    private static double sq(Camera c, double lat, double lng) {
        double dy = c.lat - lat, dx = (c.lng - lng) * Math.cos(Math.toRadians(lat));
        return dx * dx + dy * dy;
    }

    // ---- the cameras, a square at a time -------------------------------------------------

    private static long tile(double lat, double lng) {
        long ty = (long) Math.floor((lat + 90) / TILE), tx = (long) Math.floor((lng + 180) / TILE);
        return ty << 32 | tx;
    }

    /** The cameras of the square at [lat],[lng] and [ring] rings of squares around it, as far as they are loaded. */
    private static List<Camera> near(double lat, double lng, int ring) {
        List<Camera> out = new ArrayList<>();
        long center = tile(lat, lng), ty = center >>> 32, tx = center & 0xffffffffL;
        for (long y = ty - ring; y <= ty + ring; y++) {
            for (long x = tx - ring; x <= tx + ring; x++) {
                List<Camera> t = tiles.get(y << 32 | x);
                if (t != null) out.addAll(t);
            }
        }
        return out;
    }

    /**
     * Loads, or downloads, the square at [lat],[lng] and the Camera download area's rings around it (at
     * least one, the squares the alerts look into): those kept on the phone from the phone, the rest in
     * one request for the rectangle around them -- nine requests at once got the app rate-limited. After a failed request the next waits 1, 2, 4 ... up to 30 minutes.
     */
    private static void around(double lat, double lng) {
        long center = tile(lat, lng), ty = center >>> 32, tx = center & 0xffffffffL;
        List<Long> missing = new ArrayList<>();
        int ring = Math.max(1, RING);   // the alerts look one square ahead in every direction
        for (long y = ty - ring; y <= ty + ring; y++) {
            for (long x = tx - ring; x <= tx + ring; x++) {
                long key = y << 32 | x;
                if (!fresh.contains(key)) missing.add(key);
            }
        }
        if (missing.isEmpty() || busy) return;
        busy = true;
        NET.execute(() -> {
            try {
                fill(missing);
            } catch (Throwable t) {
                android.util.Log.w("UA", "speed cameras", t);
            } finally {
                busy = false;
            }
        });
    }

    private static File file(Context c, long key) {
        File dir = new File(c.getFilesDir(), "speed_cameras");
        dir.mkdirs();
        return new File(dir, (key >>> 32) + "_" + (key & 0xffffffffL) + ".json");
    }

    /** Network thread: [keys] from the phone when under a week old, else from the server. */
    private static void fill(List<Long> keys) throws Exception {
        Context c = Shapes.appContext();
        if (c == null || !ON) return;
        List<Long> remaining = new ArrayList<>();
        for (long key : keys) {
            File f = file(c, key);
            if (f.isFile() && System.currentTimeMillis() - f.lastModified() < WEEK && keep(key, f)) fresh.add(key);
            else remaining.add(key);
        }
        if (remaining.isEmpty() || System.currentTimeMillis() < retryAt) {
            // An old copy is still better than none, until the server answers.
            for (long key : remaining) if (!tiles.containsKey(key) && file(c, key).isFile()) keep(key, file(c, key));
            changed();
            return;
        }
        long y0 = Long.MAX_VALUE, y1 = Long.MIN_VALUE, x0 = Long.MAX_VALUE, x1 = Long.MIN_VALUE;
        for (long key : remaining) {
            y0 = Math.min(y0, key >>> 32); y1 = Math.max(y1, key >>> 32);
            x0 = Math.min(x0, key & 0xffffffffL); x1 = Math.max(x1, key & 0xffffffffL);
        }
        double s = y0 * TILE - 90, n = (y1 + 1) * TILE - 90, w = x0 * TILE - 180, e = (x1 + 1) * TILE - 180;
        for (String server : SERVERS) {
            try {
                List<Camera> cams = parse(new JSONObject(post(server, query(s, w, n, e))));
                Map<Long, JSONArray> saved = new HashMap<>();
                Map<Long, List<Camera>> split = new HashMap<>();
                for (long y = y0; y <= y1; y++) {
                    for (long x = x0; x <= x1; x++) {
                        saved.put(y << 32 | x, new JSONArray());
                        split.put(y << 32 | x, new ArrayList<>());
                    }
                }
                for (Camera cam : cams) {
                    long key = tile(cam.lat, cam.lng);
                    if (!split.containsKey(key)) continue;
                    split.get(key).add(cam);
                    saved.get(key).put(new JSONArray().put(cam.id).put(cam.lat).put(cam.lng).put(cam.kind).put(cam.limit == null ? "" : cam.limit));
                }
                for (Map.Entry<Long, JSONArray> t : saved.entrySet()) {
                    try (OutputStream out = new FileOutputStream(file(c, t.getKey()))) {
                        out.write(t.getValue().toString().getBytes(StandardCharsets.UTF_8));
                    }
                    tiles.put(t.getKey(), Collections.unmodifiableList(split.get(t.getKey())));
                    fresh.add(t.getKey());
                }
                failures = 0;
                android.util.Log.i("UA", "speed cameras: " + cams.size() + " in " + saved.size() + " squares from " + server);
                changed();
                return;
            } catch (Throwable t) {
                android.util.Log.w("UA", "speed cameras: " + server + " " + t);
            }
        }
        failures = Math.min(failures + 1, 6);
        retryAt = System.currentTimeMillis() + Math.min(30L, 1L << (failures - 1)) * 60 * 1000L;
        for (long key : remaining) if (!tiles.containsKey(key) && file(c, key).isFile()) keep(key, file(c, key));
        changed();
    }

    /** Puts the square saved in [f] in memory; false when it cannot be read. */
    private static boolean keep(long key, File f) {
        try {
            tiles.put(key, read(new JSONArray(text(new FileInputStream(f)))));
            return true;
        } catch (Throwable t) {
            f.delete();
            return false;
        }
    }

    private static void changed() {
        if (MAP) SavedOnMap.refresh();
    }

    private static List<Camera> read(JSONArray saved) throws Exception {
        List<Camera> out = new ArrayList<>(saved.length());
        for (int i = 0; i < saved.length(); i++) {
            JSONArray a = saved.getJSONArray(i);
            out.add(new Camera(a.getLong(0), a.getDouble(1), a.getDouble(2), a.getInt(3), a.optString(4, "")));
        }
        return Collections.unmodifiableList(out);
    }

    /** Cameras as nodes, and enforcement relations (red light, average speed, ...) by their device node. */
    static String query(double s, double w, double n, double e) {
        String bbox = String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", s, w, n, e);
        return "[out:json][timeout:60];"
                + "node[\"highway\"=\"speed_camera\"](" + bbox + ")->.c;"
                + "relation[\"type\"=\"enforcement\"](" + bbox + ")->.r;"
                + "node(r.r:\"device\")->.d;"
                + "(.c;.r;.d;);out body qt;";
    }

    static List<Camera> parse(JSONObject root) throws Exception {
        JSONArray els = root.getJSONArray("elements");
        Map<Long, double[]> where = new HashMap<>();
        Map<Long, Camera> cams = new HashMap<>();
        List<JSONObject> relations = new ArrayList<>();
        for (int i = 0; i < els.length(); i++) {
            JSONObject el = els.getJSONObject(i);
            if ("node".equals(el.optString("type"))) {
                long id = el.getLong("id");
                double lat = el.getDouble("lat"), lng = el.getDouble("lon");
                where.put(id, new double[]{lat, lng});
                JSONObject tags = el.optJSONObject("tags");
                if (tags != null && "speed_camera".equals(tags.optString("highway"))) {
                    cams.put(id, new Camera(id, lat, lng, SPEED, limit(tags.optString("maxspeed"))));
                }
            } else if ("relation".equals(el.optString("type"))) {
                relations.add(el);
            }
        }
        for (JSONObject rel : relations) {
            JSONObject tags = rel.optJSONObject("tags");
            if (tags == null) continue;
            int kind;
            switch (tags.optString("enforcement")) {
                case "maxspeed": case "mindistance": kind = SPEED; break;
                case "average_speed": kind = AVERAGE; break;
                case "traffic_signals": kind = RED_LIGHT; break;
                case "stop": kind = STOP; break;
                default: continue;
            }
            JSONArray members = rel.optJSONArray("members");
            if (members == null) continue;
            for (int i = 0; i < members.length(); i++) {
                JSONObject m = members.getJSONObject(i);
                if (!"node".equals(m.optString("type")) || !"device".equals(m.optString("role"))) continue;
                long id = m.getLong("ref");
                double[] p = where.get(id);
                if (p == null) continue;
                String limit = limit(tags.optString("maxspeed"));
                Camera node = cams.get(id);
                if (limit.isEmpty() && node != null) limit = node.limit;
                cams.put(id, new Camera(id, p[0], p[1], kind, limit));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(cams.values()));
    }

    /** OpenStreetMap's maxspeed as shown: "25 mph" stays, a bare "50" is km/h. */
    private static String limit(String maxspeed) {
        if (maxspeed == null) return "";
        String v = maxspeed.trim();
        if (v.matches("\\d+")) return v + " km/h";
        return v.matches("\\d+ ?(mph|km/h|knots)") ? v : "";
    }

    private static String post(String server, String query) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(server).openConnection();
        try {
            http.setConnectTimeout(15000);
            http.setReadTimeout(75000);
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("User-Agent", AGENT);
            http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = http.getOutputStream()) {
                out.write(("data=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8));
            }
            int code = http.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return text(http.getInputStream());
        } finally {
            http.disconnect();
        }
    }

    private static String text(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    // ---- Maps open or not --------------------------------------------------------------

    private static final class Lifecycle implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity a) { resumed++; listen(); }
        @Override public void onActivityPaused(Activity a) { resumed = Math.max(0, resumed - 1); listen(); }
        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        @Override public void onActivityDestroyed(Activity a) {}
    }
}
