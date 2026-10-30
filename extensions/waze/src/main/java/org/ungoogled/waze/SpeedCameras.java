package org.ungoogled.waze;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Speed camera alerts in Waze (issue #87): a chime and a note when a speed, red light, average speed or stop sign
 * camera is ahead on the road you are driving, from OpenStreetMap -- in every country, France included, where
 * Waze's own servers give no fixed cameras. The same data as Ungoogled Maps' Speed cameras, through OsmCameras
 * (downloads, cache, "ahead" test and chime shared with Flock cameras). No pins on Waze's map and no routing.
 * Warning of cameras is against the law in some countries; it stays off until switched on.
 */
public final class SpeedCameras {
    private SpeedCameras() {}

    static final int SPEED = 0, RED_LIGHT = 1, AVERAGE = 2, STOP = 3;

    private static final OsmCameras LAYER = new OsmCameras("speed cameras", "speedCameras", "speed_cameras",
            "org.ungoogled.waze.SPEED_CAMERA_TEST", "Ungoogled-Waze-speed-cameras/1.0", new OsmCameras.Kind() {
        @Override public String query(double s, double w, double n, double e) {
            return SpeedCameras.query(s, w, n, e);
        }

        @Override public List<OsmCameras.Camera> parse(JSONObject root) throws Exception {
            return SpeedCameras.parse(root);
        }

        @Override public String label(OsmCameras.Camera c) {
            return SpeedCameras.label(c);
        }

        @Override public String emoji(OsmCameras.Camera c) {
            switch (c.kind) {
                case RED_LIGHT: return "\uD83D\uDEA6";   // vertical traffic light
                case STOP: return "\uD83D\uDED1";        // stop sign
                default: return "\uD83D\uDCF7";          // camera
            }
        }

        @Override public OsmCameras.Camera testCamera(double lat, double lng) {
            return new OsmCameras.Camera(-1, lat, lng, SPEED, "50 km/h");
        }
    });

    /** From WazeMobileApplication.onCreate. */
    public static void init(Application a) {
        LAYER.init(a);
    }

    /** The Customization switch. */
    static void switched(boolean on) {
        LAYER.switched(on);
    }

    /** "Speed camera · 50 km/h", "Red light camera", "Average speed camera · 110 km/h", "Stop sign camera". */
    static String label(OsmCameras.Camera c) {
        String what;
        switch (c.kind) {
            case RED_LIGHT: what = "Red light camera"; break;
            case AVERAGE: what = "Average speed camera"; break;
            case STOP: what = "Stop sign camera"; break;
            default: what = "Speed camera";
        }
        return c.text == null || c.text.isEmpty() ? what : what + " · " + c.text;
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

    static List<OsmCameras.Camera> parse(JSONObject root) throws Exception {
        JSONArray els = root.getJSONArray("elements");
        Map<Long, double[]> where = new HashMap<>();
        Map<Long, OsmCameras.Camera> cams = new HashMap<>();
        List<JSONObject> relations = new ArrayList<>();
        for (int i = 0; i < els.length(); i++) {
            JSONObject el = els.getJSONObject(i);
            if ("node".equals(el.optString("type"))) {
                long id = el.getLong("id");
                double lat = el.getDouble("lat"), lng = el.getDouble("lon");
                where.put(id, new double[]{lat, lng});
                JSONObject tags = el.optJSONObject("tags");
                if (tags != null && "speed_camera".equals(tags.optString("highway"))) {
                    cams.put(id, new OsmCameras.Camera(id, lat, lng, SPEED, limit(tags.optString("maxspeed"))));
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
                OsmCameras.Camera node = cams.get(id);
                if (limit.isEmpty() && node != null) limit = node.text;
                cams.put(id, new OsmCameras.Camera(id, p[0], p[1], kind, limit));
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
}
