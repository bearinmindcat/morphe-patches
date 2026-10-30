package org.ungoogled.waze;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Flock cameras in Waze (issue #47): a chime and a note when a Flock or other license plate reader (ALPR) is
 * ahead on the road you are driving, from the cameras DeFlock's volunteers map into OpenStreetMap -- the same
 * data, downloads and "ahead" test as Ungoogled Maps' Flock cameras, through OsmCameras. No pins on Waze's map
 * and no routing around them.
 */
public final class Flock {
    private Flock() {}

    private static final OsmCameras LAYER = new OsmCameras("flock cameras", "flockCameras", "flock_cameras",
            "org.ungoogled.waze.FLOCK_TEST", "Ungoogled-Waze-flock-cameras/1.0", new OsmCameras.Kind() {
        @Override public String query(double s, double w, double n, double e) {
            return Flock.query(s, w, n, e);
        }

        @Override public List<OsmCameras.Camera> parse(JSONObject root) throws Exception {
            return Flock.parse(root);
        }

        @Override public String label(OsmCameras.Camera c) {
            return Flock.label(c);
        }

        @Override public String emoji(OsmCameras.Camera c) {
            return "\uD83D\uDCF9";   // video camera
        }

        @Override public OsmCameras.Camera testCamera(double lat, double lng) {
            return new OsmCameras.Camera(-1, lat, lng, 0, "Flock Safety");
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

    /** "Flock camera", "License plate reader · Genetec", "License plate reader". */
    static String label(OsmCameras.Camera c) {
        String maker = c.text == null ? "" : c.text.trim();
        if (maker.toLowerCase(Locale.ROOT).startsWith("flock")) return "Flock camera";
        boolean known = !maker.isEmpty() && !maker.equalsIgnoreCase("unknown");
        return known ? "License plate reader · " + maker : "License plate reader";
    }

    /** Plate readers the way DeFlock maps them (surveillance:type=ALPR), cut down on the server to position and maker. */
    static String query(double s, double w, double n, double e) {
        return "[out:json][timeout:90];"
                + "node[\"surveillance:type\"=\"ALPR\"](" + String.format(Locale.ROOT, "%.5f,%.5f,%.5f,%.5f", s, w, n, e) + ");"
                + "convert r ::id=id(),::geom=geom(),m=t[\"manufacturer\"];"
                + "out geom qt;";
    }

    static List<OsmCameras.Camera> parse(JSONObject root) throws Exception {
        JSONArray els = root.getJSONArray("elements");
        List<OsmCameras.Camera> out = new ArrayList<>(els.length());
        for (int i = 0; i < els.length(); i++) {
            JSONObject el = els.getJSONObject(i);
            JSONObject geom = el.optJSONObject("geometry");
            JSONArray at = geom == null ? null : geom.optJSONArray("coordinates");
            if (at == null || at.length() < 2) continue;
            JSONObject tags = el.optJSONObject("tags");
            out.add(new OsmCameras.Camera(el.getLong("id"), at.getDouble(1), at.getDouble(0), 0,
                    tags == null ? "" : tags.optString("m", "")));
        }
        return Collections.unmodifiableList(out);
    }
}
