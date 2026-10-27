package org.ungoogled.waze;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
 * Flock cameras in Waze (issue #47): a chime and a note when a Flock or other license plate reader (ALPR) is
 * ahead on the road you are driving, from the cameras DeFlock's volunteers map into OpenStreetMap -- the same
 * data, downloads and "ahead" test as Ungoogled Maps' Flock cameras. No pins on Waze's map and no routing
 * around them.
 *
 * Off until switched on in Customization, because it goes online: the cameras come from an OpenStreetMap server
 * (the Overpass API), in 0.25-degree squares -- the one you are in and the ring around it, about 75 km across --
 * each kept on the phone for a week. A server that fails or hangs is passed over for a while; a failed download is
 * tried again after 30 seconds, then 1, 2, 4 ... up to 30 minutes.
 *
 * The location is Waze's own: Android's passive provider hands over every fix Waze asks for (Waze asks
 * LocationManager for GPS and network fixes), so nothing turns GPS on for this. GPS fixes alert, network ones
 * only decide where to download.
 *
 * A camera is "ahead" when it lies on the line you are driving along: within 60 m of it, in front, and 20 seconds
 * away -- at least 250 m, at most 1 km. Each camera alerts once, then not again for five minutes.
 */
public final class Flock {
    private Flock() {}

    private static final String TAG = "UA";
    /** Only after `adb shell setprop log.tag.UA DEBUG` and a restart of Waze: a camera and a drive to it (see SelfTest). */
    static final String ACTION_TEST = "org.ungoogled.waze.FLOCK_TEST";

    private static final double TILE = 0.25;
    private static final int RING = 1;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static final String[] SERVERS = {
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://z.overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
    };
    private static final long[] SERVER_RESTING = new long[SERVERS.length];
    private static final boolean[] SERVER_HUNG = new boolean[SERVERS.length];
    private static final long REST_MS = 10 * 60 * 1000L, REST_HUNG_MS = 30 * 60 * 1000L;
    private static final String AGENT = "Ungoogled-Waze-flock-cameras/1.0";

    static final class Camera {
        final long id;
        final double lat, lng;
        /** The maker ("Flock Safety"); may be empty. */
        final String maker;

        Camera(long id, double lat, double lng, String maker) {
            this.id = id; this.lat = lat; this.lng = lng; this.maker = maker;
        }
    }

    private static final Map<Long, List<Camera>> tiles = new ConcurrentHashMap<>();
    private static final Set<Long> fresh = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<Long, Long> alerted = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ua-flock");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean busy;
    private static volatile long retryAt;
    private static int failures;
    private static volatile Location last;
    private static Location previous;
    private static boolean listening;
    private static final Runnable RETRY = () -> {
        Location l = last;
        if (l != null && on()) around(l.getLatitude(), l.getLongitude());
    };

    private static final LocationListener PASSIVE = new LocationListener() {
        @Override public void onLocationChanged(Location l) { located(l); }
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private static boolean on() {
        return Customization.on("flockCameras");
    }

    /** From WazeMobileApplication.onCreate. */
    public static void init(Application a) {
        try {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                IntentFilter f = new IntentFilter(ACTION_TEST);
                if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(new SelfTest(), f, Context.RECEIVER_EXPORTED);
                else a.registerReceiver(new SelfTest(), f);
                Log.d(TAG, "flock cameras: self-test receiver on, " + ACTION_TEST);
            }
            MAIN.post(Flock::listen);
        } catch (Throwable t) {
            Log.w(TAG, "flock cameras", t);
        }
    }

    /** The Customization switch. */
    static void switched(boolean on) {
        MAIN.post(Flock::listen);
    }

    /** The passive provider on while switched on; off otherwise. Main thread. */
    private static void listen() {
        Context c = Customization.appContext();
        if (c == null) return;
        boolean want = on();
        if (want == listening) return;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            if (want) {
                if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "flock cameras: no location permission yet");
                    return;
                }
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000L, 0f, PASSIVE, Looper.getMainLooper());
                Location known = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                if (known != null) located(known);
                Log.i(TAG, "flock cameras: on, listening to Waze's location");
            } else {
                lm.removeUpdates(PASSIVE);
                previous = null;
                Log.i(TAG, "flock cameras: off");
            }
            listening = want;
        } catch (Throwable t) {
            Log.w(TAG, "flock cameras: location", t);
        }
    }

    private static void located(Location l) {
        if (l == null || !on()) return;
        last = l;
        around(l.getLatitude(), l.getLongitude());
        if (!LocationManager.GPS_PROVIDER.equals(l.getProvider())) return;   // rough fixes only decide the download
        try {
            alert(l);
        } catch (Throwable t) {
            Log.w(TAG, "flock cameras: alert", t);
        }
    }

    // ---- alerts ------------------------------------------------------------------------------------

    private static void alert(Location l) {
        // Speed and heading: the fix's own while it reports moving, else worked out from the last fix 8 m or more
        // away -- some receivers, and the emulator, report 0 for both.
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
        // The camera, and any other mapped within 50 m of it: a pair at one junction chimes once.
        for (Camera cam : near(best.lat, best.lng, 1)) {
            Location.distanceBetween(best.lat, best.lng, cam.lat, cam.lng, result);
            if (result[0] <= 50) alerted.put(cam.id, now);
        }
        String label = label(best);
        Log.i(TAG, "flock camera ahead: " + label + ", " + Math.round(bestDistance) + " m");
        Chime.camera();
        Context c = Customization.appContext();
        if (c != null) Toast.makeText(c, "📹 " + label + " ahead", Toast.LENGTH_LONG).show();
    }

    /** "Flock camera", "License plate reader · Genetec", "License plate reader". */
    static String label(Camera c) {
        String maker = c.maker == null ? "" : c.maker.trim();
        if (maker.toLowerCase(Locale.ROOT).startsWith("flock")) return "Flock camera";
        boolean known = !maker.isEmpty() && !maker.equalsIgnoreCase("unknown");
        return known ? "License plate reader · " + maker : "License plate reader";
    }

    // ---- the cameras, a square at a time -----------------------------------------------------------

    private static long tile(double lat, double lng) {
        long ty = (long) Math.floor((lat + 90) / TILE), tx = (long) Math.floor((lng + 180) / TILE);
        return ty << 32 | tx;
    }

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

    /** The square at [lat],[lng] and the ring around it: from the phone when under a week old, else downloaded. */
    private static void around(double lat, double lng) {
        long center = tile(lat, lng), ty = center >>> 32, tx = center & 0xffffffffL;
        List<Long> missing = new ArrayList<>();
        for (long y = ty - RING; y <= ty + RING; y++) {
            for (long x = tx - RING; x <= tx + RING; x++) {
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
                Log.w(TAG, "flock cameras", t);
            } finally {
                busy = false;
            }
        });
    }

    private static File file(Context c, long key) {
        File dir = new File(c.getFilesDir(), "flock_cameras");
        dir.mkdirs();
        return new File(dir, (key >>> 32) + "_" + (key & 0xffffffffL) + ".json");
    }

    private static void fill(List<Long> keys) throws Exception {
        Context c = Customization.appContext();
        if (c == null || !on()) return;
        List<Long> remaining = new ArrayList<>();
        for (long key : keys) {
            File f = file(c, key);
            if (f.isFile() && System.currentTimeMillis() - f.lastModified() < WEEK && keep(key, f)) fresh.add(key);
            else remaining.add(key);
        }
        if (remaining.isEmpty() || System.currentTimeMillis() < retryAt) {
            for (long key : remaining) if (!tiles.containsKey(key) && file(c, key).isFile()) keep(key, file(c, key));
            return;
        }
        long y0 = Long.MAX_VALUE, y1 = Long.MIN_VALUE, x0 = Long.MAX_VALUE, x1 = Long.MIN_VALUE;
        for (long key : remaining) {
            y0 = Math.min(y0, key >>> 32); y1 = Math.max(y1, key >>> 32);
            x0 = Math.min(x0, key & 0xffffffffL); x1 = Math.max(x1, key & 0xffffffffL);
        }
        double s = y0 * TILE - 90, n = (y1 + 1) * TILE - 90, w = x0 * TILE - 180, e = (x1 + 1) * TILE - 180;
        for (int k : serverOrder()) {
            String server = SERVERS[k];
            try {
                JSONObject root = new JSONObject(post(server, query(s, w, n, e), 240000));
                // A query the server stopped still answers 200, with a remark: not the whole area, so not kept.
                String remark = root.optString("remark", "");
                if (remark.contains("error")) throw new java.io.IOException(remark);
                List<Camera> cams = parse(root);
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
                    saved.get(key).put(new JSONArray().put(cam.id).put(cam.lat).put(cam.lng).put(cam.maker == null ? "" : cam.maker));
                }
                for (Map.Entry<Long, JSONArray> t : saved.entrySet()) {
                    try (OutputStream out = new FileOutputStream(file(c, t.getKey()))) {
                        out.write(t.getValue().toString().getBytes(StandardCharsets.UTF_8));
                    }
                    tiles.put(t.getKey(), Collections.unmodifiableList(split.get(t.getKey())));
                    fresh.add(t.getKey());
                }
                failures = 0;
                retryAt = 0;
                synchronized (SERVER_RESTING) {
                    SERVER_RESTING[k] = 0;
                    SERVER_HUNG[k] = false;
                }
                Log.i(TAG, "flock cameras: " + cams.size() + " in " + saved.size() + " squares from " + server);
                return;
            } catch (Throwable t) {
                boolean hung = t instanceof java.net.SocketTimeoutException;
                synchronized (SERVER_RESTING) {
                    SERVER_RESTING[k] = System.currentTimeMillis() + (hung ? REST_HUNG_MS : REST_MS);
                    SERVER_HUNG[k] = hung;
                }
                Log.w(TAG, "flock cameras: " + server + " " + t);
            }
        }
        failures = Math.min(failures + 1, 7);
        long wait = Math.min(30 * 60 * 1000L, 30_000L << (failures - 1));
        retryAt = System.currentTimeMillis() + wait;
        MAIN.removeCallbacks(RETRY);
        MAIN.postDelayed(RETRY, wait + 1000L);
        for (long key : remaining) if (!tiles.containsKey(key) && file(c, key).isFile()) keep(key, file(c, key));
    }

    /** Those not resting; if all are, those that answered with an error, the soonest back first. */
    private static List<Integer> serverOrder() {
        long now = System.currentTimeMillis();
        List<Integer> out = new ArrayList<>();
        synchronized (SERVER_RESTING) {
            for (int k = 0; k < SERVERS.length; k++) if (SERVER_RESTING[k] <= now) out.add(k);
            if (out.isEmpty()) {
                for (int k = 0; k < SERVERS.length; k++) if (!SERVER_HUNG[k]) out.add(k);
                if (out.isEmpty()) for (int k = 0; k < SERVERS.length; k++) out.add(k);
                Collections.sort(out, (a, b) -> Long.compare(SERVER_RESTING[a], SERVER_RESTING[b]));
            }
        }
        return out;
    }

    private static boolean keep(long key, File f) {
        try {
            JSONArray saved = new JSONArray(text(new FileInputStream(f)));
            List<Camera> out = new ArrayList<>(saved.length());
            for (int i = 0; i < saved.length(); i++) {
                JSONArray a = saved.getJSONArray(i);
                out.add(new Camera(a.getLong(0), a.getDouble(1), a.getDouble(2), a.optString(3, "")));
            }
            tiles.put(key, Collections.unmodifiableList(out));
            return true;
        } catch (Throwable t) {
            f.delete();
            return false;
        }
    }

    /** Plate readers the way DeFlock maps them (surveillance:type=ALPR), cut down on the server to position and maker. */
    static String query(double s, double w, double n, double e) {
        return "[out:json][timeout:90];"
                + "node[\"surveillance:type\"=\"ALPR\"](" + String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", s, w, n, e) + ");"
                + "convert r ::id=id(),::geom=geom(),m=t[\"manufacturer\"];"
                + "out geom qt;";
    }

    static List<Camera> parse(JSONObject root) throws Exception {
        JSONArray els = root.getJSONArray("elements");
        List<Camera> out = new ArrayList<>(els.length());
        for (int i = 0; i < els.length(); i++) {
            JSONObject el = els.getJSONObject(i);
            JSONObject geom = el.optJSONObject("geometry");
            JSONArray at = geom == null ? null : geom.optJSONArray("coordinates");
            if (at == null || at.length() < 2) continue;
            JSONObject tags = el.optJSONObject("tags");
            out.add(new Camera(el.getLong("id"), at.getDouble(1), at.getDouble(0), tags == null ? "" : tags.optString("m", "")));
        }
        return Collections.unmodifiableList(out);
    }

    private static String post(String server, String query, int readTimeoutMs) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(server).openConnection();
        try {
            http.setConnectTimeout(15000);
            http.setReadTimeout(readTimeoutMs);
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

    // ---- the self-test (debug only) -----------------------------------------------------------------

    /**
     * `am broadcast -a org.ungoogled.waze.FLOCK_TEST --ed lat .. --ed lng ..` puts a made-up Flock camera there,
     * in memory only (its squares count as downloaded, so nothing is fetched), with boolean enable (switch Flock
     * cameras on first) and boolean drive (feed a drive towards it through located(), from 800 m to 150 m at
     * 15 m/s, one fix a second; without it, drive with `adb emu geo fix` -- Waze's own fixes reach located()).
     */
    private static final class SelfTest extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (intent.getBooleanExtra("enable", false)) Customization.setForTest("flockCameras", true);
                double lat = intent.getDoubleExtra("lat", 0), lng = intent.getDoubleExtra("lng", 0);
                long key = tile(lat, lng), ty = key >>> 32, tx = key & 0xffffffffL;
                for (long y = ty - RING - 1; y <= ty + RING + 1; y++) {
                    for (long x = tx - RING - 1; x <= tx + RING + 1; x++) fresh.add(y << 32 | x);
                }
                List<Camera> t = new ArrayList<>();
                List<Camera> had = tiles.get(key);
                if (had != null) t.addAll(had);
                t.add(new Camera(-1, lat, lng, "Flock Safety"));
                tiles.put(key, Collections.unmodifiableList(t));
                alerted.remove(-1L);
                previous = null;
                Log.i(TAG, "flock self-test: camera at " + lat + "," + lng);
                if (!intent.getBooleanExtra("drive", false)) return;
                double bearing = intent.getDoubleExtra("bearing", 0);   // the direction driven, towards the camera
                int[] away = {800, 600, 450, 300, 250, 150};
                for (int i = 0; i < away.length; i++) {
                    int d = away[i];
                    MAIN.postDelayed(() -> {
                        double back = Math.toRadians(bearing + 180);
                        double dLat = d * Math.cos(back) / 111_320.0;
                        double dLng = d * Math.sin(back) / (111_320.0 * Math.cos(Math.toRadians(lat)));
                        Location l = new Location(LocationManager.GPS_PROVIDER);
                        l.setLatitude(lat + dLat);
                        l.setLongitude(lng + dLng);
                        l.setSpeed(15f);
                        l.setBearing((float) bearing);
                        l.setAccuracy(5f);
                        l.setTime(System.currentTimeMillis());
                        l.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
                        Log.d(TAG, "flock self-test: " + d + " m before the camera");
                        located(l);
                    }, 1000L * (i + 1));
                }
            } catch (Throwable t) {
                Log.w(TAG, "flock self-test", t);
            }
        }
    }
}
