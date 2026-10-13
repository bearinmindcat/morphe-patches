package org.ungoogled.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Avoid Flock cameras: driving directions steered around them.
 *
 * Google's routing takes no "avoid this spot", but Maps' directions request carries a list of
 * pass-through points ("viapoints": a position and the leg it belongs to) that Maps itself always
 * leaves empty, and the route must pass through every one. So when Google's driving route passes Flock
 * cameras, a camera-free path between the same two ends is worked out with BRouter (an OpenStreetMap
 * router, brouter.de) -- route, block each camera on the route with a 30 m no-go circle, route again,
 * until none is left, giving up past 10 minutes or half again the plain time, whichever is more -- and
 * Maps is asked for the directions again with a pass-through point in every stretch where that path
 * leaves Google's. Google then draws, times and navigates its own route through those points. Where
 * Google still strays onto a camera it is asked once more, pinned where it strayed; a steered route that
 * is no better than the plain one is dropped, and Google's own route comes back.
 *
 * What goes online: the route's two ends and the cameras' positions to brouter.de, and the Flock
 * cameras of the squares along the route from OpenStreetMap (the Proxy setting covers both). Trips with
 * stops on the way are left alone, as is a route asked for while navigating.
 */
public final class Steer {
    private Steer() {}

    /** A camera this close to the route counts as on it. */
    private static final double ON_ROUTE_M = 25;
    /** No-go circle around a camera for BRouter. */
    private static final int NOGO_M = 30;
    /** Where the camera-free path is this far from Google's route, it has left it. */
    private static final double LEFT_ROUTE_M = 60;
    private static final int MAX_VIAS = 12;
    /** The camera-free path may take this much longer than the plain one: the larger of the two. */
    private static final double EXTRA_SECONDS = 600, EXTRA_SHARE = 0.5;
    /** Google's steered route may be at most this much longer than its plain one, else it is dropped. */
    private static final double MAX_LENGTH_RATIO = 1.5;
    /**
     * A pass-through list is offered to every request for this long after it is made, or until the
     * answer to one carrying it comes back: Maps builds each fetch's request twice, and only one is sent.
     */
    private static final long ARMED_MS = 90_000;
    /** Plain answers for a trip while its steered one is awaited, before giving up on it. */
    private static final int MAX_PLAIN_WHILE_ASKING = 3;
    private static final String BROUTER = "https://brouter.de/brouter";
    private static final String AGENT = "Ungoogled-Maps-avoid-flock/1.0";

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ua-steer");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * The directions screen's request session (wkn), from its start: its controller re-requests the
     * directions the way Maps does when a route option changes, and the screen shows that answer.
     */
    private static volatile java.lang.ref.WeakReference<Object> session = new java.lang.ref.WeakReference<>(null);
    /**
     * Google's plain route from an answer that came with no directions screen open (the place sheet's
     * prefetch of the drive): worked out only once a directions screen opens, so a place merely looked at
     * goes nowhere.
     */
    private static volatile double[] waiting;
    /** The pass-through points for the next request, and until when they are offered. */
    private static volatile List<double[]> armed;
    private static volatile long armedUntil;
    /** When the points last went into a request. */
    private static volatile long injectedAt;

    /** One trip being steered: its ends, the camera-free path, the points asked for and how it went. */
    private static final class Trip {
        final String key;
        final double[] clean;
        final int before;
        /** Google's plain route, in metres. */
        double plainLength;
        List<double[]> asked = new ArrayList<>();
        int rounds;
        /** When the last ask went out, and plain answers that came back since. */
        long askedAt;
        int plainSince;

        Trip(String key, double[] clean, int before) {
            this.key = key; this.clean = clean; this.before = before;
        }
    }

    private static volatile Trip current;
    /** Trips already worked out (their pass-through points), and trips given up on. */
    private static final Map<String, Trip> done = Collections.synchronizedMap(new HashMap<>());
    /**
     * The trip whose steered route the directions screen shows now, and the one being navigated: Maps
     * sends its reroutes with no pass-through points, so the points still ahead are put back in.
     */
    private static volatile Trip steered, navTrip;
    private static final Set<String> declined = Collections.synchronizedSet(new HashSet<>());
    private static volatile String told;

    /** Per-request and per-answer log lines, only with `adb shell setprop log.tag.UA DEBUG`. */
    private static boolean debug() {
        return android.util.Log.isLoggable("UA", android.util.Log.DEBUG);
    }

    private static boolean on() {
        return Shapes.flockAvoidPatched() && SpeedCameras.FLOCK_AVOID;
    }

    // ---- hooks -------------------------------------------------------------------------------

    /** From the request builder: [list] (Maps' own pass-through points, always empty) for [request]. */
    public static Object vias(Object request, Object list) {
        try {
            if (debug()) {
                android.util.Log.d("UA", "avoid flock: request built here, with Maps' own "
                        + (list instanceof List ? ((List<?>) list).size() : -1) + " pass-through points", new Throwable());
            }
            List<double[]> points = armed;
            boolean reroute = false;
            if (points == null) {
                points = debugVias();
                if (points == null) {
                    points = navigationVias(list);
                    reroute = points != null;
                }
                if (points == null) return list;
            } else if (!on()) {
                return list;
            } else if (System.currentTimeMillis() > armedUntil) {
                armed = null;
                return list;
            }
            if (reroute) android.util.Log.i("UA", "avoid flock: " + points.size() + " pass-through points kept for navigation");
            // A trip with stops is never steered; Maps' own requests often fill the waypoints in later.
            List<?> waypoints = waypoints(request);
            if (debug()) android.util.Log.d("UA", "avoid flock: request mode " + requestMode(request) + ", "
                    + (waypoints == null ? "?" : String.valueOf(waypoints.size())) + " waypoints");
            if (waypoints != null && waypoints.size() > 2) return list;
            List<Object> out = new ArrayList<>();
            if (list instanceof List) out.addAll((List<?>) list);
            for (double[] p : points) out.add(via(p[0], p[1], 0));
            injectedAt = System.currentTimeMillis();
            if (debug()) android.util.Log.d("UA", "avoid flock: " + points.size() + " pass-through points in the request");
            return immutable(out);
        } catch (Throwable t) {
            android.util.Log.w("UA", "avoid flock: vias", t);
            return list;
        }
    }

    /**
     * Test harness, only with `adb shell setprop log.tag.UA DEBUG`: pass-through points from
     * files/ua_via.txt ("lat,lng" per line) for every request, to see how Google treats them.
     */
    private static List<double[]> debugVias() {
        try {
            if (!android.util.Log.isLoggable("UA", android.util.Log.DEBUG)) return null;
            Context c = Shapes.appContext();
            java.io.File f = c == null ? null : new java.io.File(c.getFilesDir(), "ua_via.txt");
            if (f == null || !f.isFile()) return null;
            List<double[]> out = new ArrayList<>();
            for (String line : text(new java.io.FileInputStream(f)).split("\\s+")) {
                String[] p = line.trim().split(",");
                if (p.length >= 2) out.add(new double[]{Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())});
            }
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** From the navigation camera hook: guidance started or ended. */
    static void navigation(boolean on) {
        navTrip = on ? steered : null;
        if (on && navTrip != null) android.util.Log.i("UA", "avoid flock: navigating the steered route, " + navTrip.asked.size() + " points");
    }

    /**
     * While navigating a steered route, for a request with no pass-through points of its own (a reroute,
     * or the faster-route check): the points still ahead along the camera-free path; else null.
     */
    private static List<double[]> navigationVias(Object list) {
        Trip t = navTrip;
        if (t == null || !on() || !SpeedCameras.navigatingNow()) return null;
        if (list instanceof List && !((List<?>) list).isEmpty()) return null;
        android.location.Location l = SpeedCameras.lastLocation();
        if (l == null) return t.asked.isEmpty() ? null : t.asked;
        double[] here = {l.getLatitude(), l.getLongitude()};
        List<double[]> out = new ArrayList<>();
        if (t.clean != null) {
            int at = nearestIndex(t.clean, here);
            for (double[] p : t.asked) {
                if (nearestIndex(t.clean, p) > at + 2 && metres(here[0], here[1], p[0], p[1]) > 100) out.add(p);
            }
        } else {
            for (double[] p : t.asked) if (metres(here[0], here[1], p[0], p[1]) > 300) out.add(p);
        }
        return out.isEmpty() ? null : out;
    }

    /** From the directions screen, as it starts its request session [s] (prefetched directions too). */
    public static void session(Object s) {
        session = new java.lang.ref.WeakReference<>(s);
        final double[] route = waiting;
        waiting = null;
        if (route != null && on()) {
            WORK.execute(() -> {
                try {
                    plan(route);
                } catch (Throwable t) {
                    android.util.Log.w("UA", "avoid flock", t);
                }
            });
        }
    }

    /** Whether a directions screen is open: the last session started and has not ended. */
    private static boolean sessionOpen() {
        Object s = session.get();
        try {
            return s != null && sessionActive(s);
        } catch (Throwable t) {
            return false;
        }
    }

    /** From the directions fetcher: Google's answer [response] to [request]. */
    public static void response(Object f, Object request, Object response) {
        try {
            if (response == null) return;
            int[] n = counts(request);
            int trips = tripCount(response);
            if (debug()) android.util.Log.d("UA", "avoid flock: answer with " + trips + " trips to a request with "
                    + (n == null ? "?" : n[0] + " waypoints, " + n[1] + " pass-through points"));
            if (!on() || n == null || n[0] != 2) {           // off, no trip, or one with stops
                if (n != null && n[1] == 0 && !SpeedCameras.navigatingNow()) steered = null;
                return;
            }
            double[] route = null;
            for (int i = 0; i < trips && route == null; i++) {
                if (tripMode(response, i) == 0) route = polyline(response, i);   // ckaj.DRIVE
            }
            if (route == null || route.length < 4) {
                // Google found no driving route through the points (the screen shows its error page):
                // give the trip up and ask for Google's own route again.
                Trip t = current;
                if (n[1] > 0 && t != null) {
                    WORK.execute(() -> giveUp(t, "Couldn't steer around the Flock cameras on this route"));
                }
                return;
            }
            if (n[1] > 0) {
                // How close Google's driving route came to each point it was asked to pass.
                List<double[]> asked = current != null ? current.asked : debugVias();
                StringBuilder d = new StringBuilder();
                if (asked != null) for (double[] p : asked) d.append(Math.round(distanceToLine(p[0], p[1], route))).append(" m ");
                android.util.Log.i("UA", "avoid flock: driving route passes its points at " + d);
            }
            final double[] google = route;
            final boolean steered = n[1] > 0;
            if (!steered && !sessionOpen()) {
                waiting = google;
                return;
            }
            if (steered) armed = null;     // the steered answer is in: stop offering the points
            WORK.execute(() -> {
                try {
                    if (steered) checkSteered(google);
                    else plan(google);
                } catch (Throwable t) {
                    android.util.Log.w("UA", "avoid flock", t);
                }
            });
        } catch (Throwable t) {
            android.util.Log.w("UA", "avoid flock: response", t);
        }
    }

    // ---- planning ----------------------------------------------------------------------------

    private static String tripKey(double[] route) {
        int n = route.length;
        return String.format(Locale.ROOT, "%.3f,%.3f>%.3f,%.3f", route[0], route[1], route[n - 2], route[n - 1]);
    }

    /** Google's plain route [google]: when it passes Flock cameras, a camera-free path and a second ask. */
    private static void plan(double[] google) throws Exception {
        String key = tripKey(google);
        Trip shown = steered;
        if (shown != null && !shown.key.equals(key) && !SpeedCameras.navigatingNow()) steered = null;
        if (declined.contains(key)) return;
        // A plain answer while the steered one is awaited (Maps' own refreshes, or the points not taken).
        Trip asking = current;
        if (asking != null && asking.key.equals(key) && armed != null) {
            if (++asking.plainSince >= MAX_PLAIN_WHILE_ASKING) {
                armed = null;
                declined.add(key);
                current = null;
                tellOnce(key + "#declined", "Couldn't steer around the Flock cameras on this route");
            }
            return;
        }
        List<SpeedCameras.Camera> cams = SpeedCameras.flocksAlong(google);
        if (cams == null) return;
        List<SpeedCameras.Camera> hits = onRoute(google, cams);
        android.util.Log.i("UA", "avoid flock: Google's route " + key + " passes " + hits.size() + " Flock cameras");
        if (hits.isEmpty()) {
            tellOnce(key, "No Flock cameras on this route");
            return;
        }
        if (SpeedCameras.navigatingNow()) {
            tellOnce(key, count(hits.size()) + " on this route");
            return;
        }
        // Asked before (Maps refreshes its directions now and then): the same points again.
        Trip known = done.get(key);
        if (known != null) {
            Trip t = new Trip(key, known.clean, hits.size());
            t.rounds = 2;
            t.plainLength = length(google);
            current = t;
            ask(t, known.asked);
            return;
        }
        double[] path = cleanPath(google, cams, hits);
        if (path == null) {
            declined.add(key);
            tellOnce(key, count(hits.size()) + " on this route; no way around them within 10 minutes or half again the time");
            return;
        }
        List<double[]> points = detours(path, google);
        if (points.isEmpty()) return;
        Trip t = new Trip(key, path, hits.size());
        t.rounds = 1;
        t.plainLength = length(google);
        current = t;
        ask(t, points);
    }

    /** The answer to a steered request, [google]: done, one more ask, or back to Google's own route. */
    private static void checkSteered(double[] google) throws Exception {
        Trip t = current;
        if (t == null || !t.key.equals(tripKey(google))) return;
        List<SpeedCameras.Camera> cams = SpeedCameras.flocksAlong(google);
        if (cams == null) return;
        List<SpeedCameras.Camera> hits = onRoute(google, cams);
        android.util.Log.i("UA", "avoid flock: steered route passes " + hits.size() + " Flock cameras (round " + t.rounds + ", was " + t.before + ")");
        boolean lastRound = t.clean == null || t.rounds >= 2 || SpeedCameras.navigatingNow();
        double steeredLength = length(google);
        android.util.Log.i("UA", String.format(Locale.ROOT, "avoid flock: steered route %.1f km, plain %.1f km",
                steeredLength / 1000, t.plainLength / 1000));
        if (t.plainLength > 0 && steeredLength > MAX_LENGTH_RATIO * t.plainLength) {
            giveUp(t, "Avoiding the Flock cameras on this route would make it too long");
            return;
        }
        if (hits.size() >= t.before && lastRound) {
            giveUp(t, "Couldn't steer around the Flock cameras on this route");
            return;
        }
        String extra = t.plainLength > 0 ? " (" + extra(steeredLength - t.plainLength) + ")" : "";
        if (hits.isEmpty() || lastRound) {
            done.put(t.key, t);
            steered = t;
            tellOnce(t.key + "#steered", (hits.isEmpty()
                    ? "Route steered around " + count(t.before)
                    : "Route steered: " + count(hits.size()) + " left of " + t.before) + extra);
            return;
        }
        // Where Google still strayed from the camera-free path, pin it to that path as well.
        List<double[]> all = new ArrayList<>(t.asked);
        for (double[] p : detours(t.clean, google)) {
            boolean near = false;
            for (double[] q : all) near |= metres(p[0], p[1], q[0], q[1]) < 300;
            if (!near && all.size() < MAX_VIAS) all.add(p);
        }
        if (all.size() == t.asked.size()) {
            done.put(t.key, t);
            steered = t;
            tellOnce(t.key + "#steered", "Route steered: " + count(hits.size()) + " left of " + t.before + extra);
            return;
        }
        t.rounds++;
        ask(t, sortAlong(all, t.clean));
    }

    /** No better than Google's own (or no route at all): drop the points and let the plain route come back. */
    private static void giveUp(Trip t, String why) {
        if (current != t) return;
        armed = null;
        declined.add(t.key);
        done.remove(t.key);
        current = null;
        if (steered == t) steered = null;
        tellOnce(t.key + "#declined", why);
        refetchNow();
    }

    /** The length of the route [line] in metres. */
    private static double length(double[] line) {
        double out = 0;
        for (int i = 0; i + 3 < line.length; i += 2) out += metres(line[i], line[i + 1], line[i + 2], line[i + 3]);
        return out;
    }

    /** "+2.3 mi" where miles are the custom (US, UK, Liberia, Myanmar), else "+3.7 km". */
    private static String extra(double metres) {
        String country = Locale.getDefault().getCountry();
        boolean miles = "US".equals(country) || "GB".equals(country) || "LR".equals(country) || "MM".equals(country);
        double v = miles ? metres / 1609.344 : metres / 1000;
        return String.format(Locale.getDefault(), "%+.1f %s", v, miles ? "mi" : "km");
    }

    /** Offers [points] to the next request and runs the last directions fetch again. */
    private static void ask(Trip t, List<double[]> points) {
        t.asked = points;
        t.askedAt = System.currentTimeMillis();
        t.plainSince = 0;
        armed = points;
        armedUntil = System.currentTimeMillis() + ARMED_MS;
        android.util.Log.i("UA", "avoid flock: asking again through " + points.size() + " points");
        refetchNow();
        // Maps can still be busy with the last answer: when no request has taken the points, once more.
        final long asked = t.askedAt;
        MAIN.postDelayed(() -> {
            if (armed != null && injectedAt < asked) {
                android.util.Log.i("UA", "avoid flock: no request took the points yet, asking once more");
                refetchNow();
            }
        }, 25_000);
    }

    /** Runs Maps' last directions fetch again, a moment from now: Maps finishes with the last answer first. */
    private static void refetchNow() {
        MAIN.postDelayed(() -> {
            try {
                Object s = session.get();
                if (s == null) {
                    android.util.Log.i("UA", "avoid flock: no directions session to ask again");
                    return;
                }
                android.util.Log.i("UA", "avoid flock: fetching the directions again");
                refetch(s);
            } catch (Throwable e) {
                android.util.Log.w("UA", "avoid flock: refetch", e);
            }
        }, 1500);
    }

    /** A BRouter path: its points and its time. */
    private static final class Path {
        final double[] points;
        final double seconds;

        Path(double[] points, double seconds) {
            this.points = points; this.seconds = seconds;
        }
    }

    /**
     * BRouter's fastest path from [google]'s start to its end, with a 30 m no-go circle on every Flock
     * camera on Google's route [hits] and on its own, again until none is left (at most eight rounds) or the
     * path gets more than 10 minutes / half again slower than BRouter's plain one. The path with the fewest
     * cameras within that, if it has fewer than Google's route; else null.
     */
    private static double[] cleanPath(double[] google, List<SpeedCameras.Camera> cams, List<SpeedCameras.Camera> hits) throws Exception {
        int n = google.length;
        double lat1 = google[0], lng1 = google[1], lat2 = google[n - 2], lng2 = google[n - 1];
        Path plain = brouter(lat1, lng1, lat2, lng2, Collections.emptyList());
        if (plain == null) return null;
        double limit = plain.seconds + Math.max(EXTRA_SECONDS, EXTRA_SHARE * plain.seconds);
        List<SpeedCameras.Camera> nogos = new ArrayList<>(hits);
        for (SpeedCameras.Camera c : onRoute(plain.points, cams)) if (!nogos.contains(c)) nogos.add(c);
        double[] best = null;
        int bestHits = hits.size();
        for (int round = 0; round < 8; round++) {
            Path path = brouter(lat1, lng1, lat2, lng2, nogos);
            if (path == null) break;
            List<SpeedCameras.Camera> around = SpeedCameras.flocksAlong(path.points);
            List<SpeedCameras.Camera> on = onRoute(path.points, around == null ? cams : around);
            android.util.Log.i("UA", String.format(Locale.ROOT, "avoid flock: BRouter round %d, %d blocked, %d on its path, %.1f min (plain %.1f)",
                    round + 1, nogos.size(), on.size(), path.seconds / 60, plain.seconds / 60));
            if (path.seconds > limit) break;
            if (on.size() < bestHits) {
                best = path.points;
                bestHits = on.size();
            }
            if (on.isEmpty()) break;
            int added = 0;
            for (SpeedCameras.Camera c : on) {
                if (!nogos.contains(c)) {
                    nogos.add(c);
                    added++;
                }
            }
            if (added == 0) break;
        }
        return best;
    }

    /**
     * The pass-through points that hold Google to [path]: for every stretch of [path] more than 60 m from
     * [google] and over 100 m long, its point farthest from [google], and on a stretch over 3 km two more
     * at a quarter and three quarters of the way. In order along [path], at most twelve.
     */
    private static List<double[]> detours(double[] path, double[] google) {
        List<double[]> out = new ArrayList<>();
        int n = path.length / 2;
        int start = -1;
        for (int i = 0; i <= n; i++) {
            boolean off = i < n && distanceToLine(path[2 * i], path[2 * i + 1], google) > LEFT_ROUTE_M;
            if (off && start < 0) start = i;
            if (!off && start >= 0) {
                int end = i - 1;
                double length = 0, far = -1;
                int apex = start;
                for (int j = start; j <= end; j++) {
                    if (j > start) length += metres(path[2 * j - 2], path[2 * j - 1], path[2 * j], path[2 * j + 1]);
                    double d = distanceToLine(path[2 * j], path[2 * j + 1], google);
                    if (d > far) {
                        far = d;
                        apex = j;
                    }
                }
                if (length >= 100) {
                    if (length > 3000) out.add(pointAt(path, start, end, 0.25));
                    out.add(new double[]{path[2 * apex], path[2 * apex + 1]});
                    if (length > 3000) out.add(pointAt(path, start, end, 0.75));
                }
                start = -1;
            }
        }
        while (out.size() > MAX_VIAS) out.remove(out.size() / 2);
        return out;
    }

    /** The point [fraction] of the way along [path] from point [from] to point [to]. */
    private static double[] pointAt(double[] path, int from, int to, double fraction) {
        double total = 0;
        for (int j = from + 1; j <= to; j++) total += metres(path[2 * j - 2], path[2 * j - 1], path[2 * j], path[2 * j + 1]);
        double want = total * fraction, run = 0;
        for (int j = from + 1; j <= to; j++) {
            run += metres(path[2 * j - 2], path[2 * j - 1], path[2 * j], path[2 * j + 1]);
            if (run >= want) return new double[]{path[2 * j], path[2 * j + 1]};
        }
        return new double[]{path[2 * to], path[2 * to + 1]};
    }

    /** [points] in the order [path] reaches them. */
    private static List<double[]> sortAlong(List<double[]> points, double[] path) {
        List<double[]> out = new ArrayList<>(points);
        Collections.sort(out, (a, b) -> Integer.compare(nearestIndex(path, a), nearestIndex(path, b)));
        return out;
    }

    private static int nearestIndex(double[] path, double[] p) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < path.length / 2; i++) {
            double d = metres(p[0], p[1], path[2 * i], path[2 * i + 1]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ---- geometry ----------------------------------------------------------------------------

    /** The cameras of [cams] within 25 m of the route [line] (lat, lng, lat, lng, ...). */
    static List<SpeedCameras.Camera> onRoute(double[] line, List<SpeedCameras.Camera> cams) {
        List<SpeedCameras.Camera> out = new ArrayList<>();
        double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;
        for (int i = 0; i + 1 < line.length; i += 2) {
            minLat = Math.min(minLat, line[i]); maxLat = Math.max(maxLat, line[i]);
            minLng = Math.min(minLng, line[i + 1]); maxLng = Math.max(maxLng, line[i + 1]);
        }
        for (SpeedCameras.Camera c : cams) {
            if (c.lat < minLat - 0.001 || c.lat > maxLat + 0.001 || c.lng < minLng - 0.001 || c.lng > maxLng + 0.001) continue;
            if (distanceToLine(c.lat, c.lng, line) <= ON_ROUTE_M) out.add(c);
        }
        return out;
    }

    /** Metres from [lat],[lng] to the nearest point of the polyline [line]. */
    static double distanceToLine(double lat, double lng, double[] line) {
        double k = Math.cos(Math.toRadians(lat)) * 111320, m = 110540;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 3 < line.length; i += 2) {
            double ax = (line[i + 1] - lng) * k, ay = (line[i] - lat) * m;
            double bx = (line[i + 3] - lng) * k, by = (line[i + 2] - lat) * m;
            // quick reject: both ends farther than the best so far on the same side
            if ((ax > best && bx > best) || (ax < -best && bx < -best) || (ay > best && by > best) || (ay < -best && by < -best)) continue;
            double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
            double px = ax + t * dx, py = ay + t * dy;
            best = Math.min(best, Math.sqrt(px * px + py * py));
        }
        return best;
    }

    static double metres(double lat1, double lng1, double lat2, double lng2) {
        double k = Math.cos(Math.toRadians((lat1 + lat2) / 2)) * 111320;
        return Math.hypot((lng2 - lng1) * k, (lat2 - lat1) * 110540);
    }

    // ---- BRouter -----------------------------------------------------------------------------

    /** BRouter's car-fast path between two points with [nogos] blocked, or null when it does not answer. */
    private static Path brouter(double lat1, double lng1, double lat2, double lng2, List<SpeedCameras.Camera> nogos) {
        StringBuilder url = new StringBuilder(BROUTER).append(String.format(Locale.ROOT,
                "?lonlats=%.6f,%.6f|%.6f,%.6f&profile=car-fast&alternativeidx=0&format=geojson", lng1, lat1, lng2, lat2));
        if (!nogos.isEmpty()) {
            url.append("&nogos=");
            for (int i = 0; i < nogos.size(); i++) {
                SpeedCameras.Camera c = nogos.get(i);
                if (i > 0) url.append('|');
                url.append(String.format(Locale.ROOT, "%.6f,%.6f,%d", c.lng, c.lat, NOGO_M));
            }
        }
        try {
            HttpURLConnection http = (HttpURLConnection) new URL(url.toString()).openConnection();
            try {
                http.setConnectTimeout(15000);
                http.setReadTimeout(60000);
                http.setRequestProperty("User-Agent", AGENT);
                int code = http.getResponseCode();
                if (code != 200) {
                    String why = "";
                    try {
                        why = text(http.getErrorStream());
                    } catch (Throwable ignored) {
                    }
                    android.util.Log.w("UA", "avoid flock: BRouter HTTP " + code + " " + why.replace('\n', ' ').trim());
                    return null;
                }
                JSONObject feature = new JSONObject(text(http.getInputStream())).getJSONArray("features").getJSONObject(0);
                JSONArray coords = feature.getJSONObject("geometry").getJSONArray("coordinates");
                double[] out = new double[coords.length() * 2];
                for (int i = 0; i < coords.length(); i++) {
                    JSONArray c = coords.getJSONArray(i);
                    out[2 * i] = c.getDouble(1);
                    out[2 * i + 1] = c.getDouble(0);
                }
                return new Path(out, feature.getJSONObject("properties").optDouble("total-time", 0));
            } finally {
                http.disconnect();
            }
        } catch (Throwable t) {
            android.util.Log.w("UA", "avoid flock: BRouter " + t);
            return null;
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

    private static String count(int n) {
        return n == 1 ? "1 Flock camera" : n + " Flock cameras";
    }

    /** A note, once per [key]: Maps refreshes its directions now and then, and each refresh comes back here. */
    private static void tellOnce(String key, String text) {
        if (key.equals(told)) return;
        told = key;
        android.util.Log.i("UA", "avoid flock: " + text);
        MAIN.post(() -> {
            Context c = Shapes.appContext();
            if (c != null) Toast.makeText(c, "📹 " + text, Toast.LENGTH_LONG).show();
        });
    }

    // ---- written by the patch against Maps' classes --------------------------------------------

    /** Rewritten by the patch: {waypoints, pass-through points} in a request. */
    static int[] counts(Object request) { return null; }

    /** Rewritten by the patch: a pass-through point at [lat],[lng] on leg [leg]. */
    static Object via(double lat, double lng, int leg) { return null; }

    /** Rewritten by the patch: the request parameters' waypoints, start first. */
    static List<?> waypoints(Object request) { return null; }

    /** Rewritten by the patch: the request parameters' travel mode (0 driving, 6 several), or -1. */
    static int requestMode(Object params) { return -1; }

    /** Rewritten by the patch: Maps' own immutable list of [items]. */
    static Object immutable(List<Object> items) { return items; }

    /** Rewritten by the patch: the number of trips in a directions answer. */
    static int tripCount(Object response) { return 0; }

    /** Rewritten by the patch: trip [i]'s travel mode (0 = driving). */
    static int tripMode(Object response, int i) { return -1; }

    /** Rewritten by the patch: trip [i]'s route as lat, lng, lat, lng, ... in degrees, or null. */
    static double[] polyline(Object response, int i) { return null; }

    /** Rewritten by the patch: whether the session [s] is still going (its controller has a session key). */
    static boolean sessionActive(Object s) { return false; }

    /**
     * Rewritten by the patch: the session's controller asks for its directions again, as for a changed
     * route option (an OPTIONS_CHANGED request); nothing when the session has ended.
     */
    static void refetch(Object session) {}
}
