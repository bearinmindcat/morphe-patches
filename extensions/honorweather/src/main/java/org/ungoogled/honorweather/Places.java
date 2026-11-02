package org.ungoogled.honorweather;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/**
 * The place keys the app keeps for each city ("om<lat>_<lon>", two decimals) and the names behind them. Open-Meteo has
 * no reverse geocoding, so a place gets its name from the city search that found it, or from the app's list of
 * suggested cities (kept here, in the app's own preferences); the current location, which the user never searched, is
 * called what the app calls it ("Current location") unless it lies at such a city.
 */
final class Places {
    private static final String PREFS = "openmeteo_places";
    private static final int KEEP = 300;

    private Places() {
    }

    static String key(double lat, double lon) {
        return "om" + OpenMeteo.coordinate(lat) + "_" + OpenMeteo.coordinate(lon);
    }

    /** {lat, lon} of a key, or null when it is not ours. */
    static double[] position(String key) {
        if (key == null || !key.startsWith("om")) return null;
        int split = key.indexOf('_', 3);
        if (split < 0) return null;
        try {
            return new double[] {Double.parseDouble(key.substring(2, split)), Double.parseDouble(key.substring(split + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static SharedPreferences store() {
        Context context = Weather.context();
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS);
    }

    /** Remembers what a search said about a place (name, region, country, time zone). */
    static void remember(String key, JSONObject place) {
        SharedPreferences store = store();
        if (store == null) return;
        Map<String, ?> all = store.getAll();
        SharedPreferences.Editor editor = store.edit();
        if (all.size() > KEEP) editor.clear();
        editor.putString(key, place.toString()).apply();
    }

    /** What a search said about this place or one within about a kilometre, else null. */
    static JSONObject named(double lat, double lon) {
        SharedPreferences store = store();
        if (store == null) return null;
        String exact = store.getString(key(lat, lon), null);
        try {
            if (exact != null) return new JSONObject(exact);
            for (Map.Entry<String, ?> entry : store.getAll().entrySet()) {
                if (!(entry.getValue() instanceof String)) continue;
                JSONObject place = new JSONObject((String) entry.getValue());
                double[] p = entry.getKey().startsWith("accu:")
                        ? new double[] {place.optDouble("Latitude", 999), place.optDouble("Longitude", 999)} : position(entry.getKey());
                if (p != null && Math.abs(p[0] - lat) < 0.0151 && Math.abs(p[1] - lon) < 0.0151) return place;
            }
        } catch (JSONException e) {
            return null;
        }
        return null;
    }

    /**
     * One of the app's suggested cities (its "usual cities" lists, which name the international supplier's city ids:
     * "native||English||cityId:N||abbreviation||time zone||country"): {native or English name, English name, time
     * zone, country}, or null.
     */
    static String[] suggested(String key) {
        Context context = Weather.context();
        if (context == null || key == null) return null;
        android.content.res.Resources resources = context.getResources();
        for (String array : new String[] {"usual_citys", "usual_citys_oversea", "usual_citys_china"}) {
            int id = resources.getIdentifier(array, "array", context.getPackageName());
            if (id == 0) continue;
            for (String item : resources.getStringArray(id)) {
                String[] f = item.split("\\|\\|", -1);
                if (f.length < 3 || !f[2].equals("cityId:" + key)) continue;
                String english = f[1];
                return new String[] {f[0].isEmpty() ? english : f[0], english, f.length > 4 ? f[4] : "", f.length > 5 ? f[5] : ""};
            }
        }
        return null;
    }

    /** What was found for a supplier city id before, or null. */
    static JSONObject known(String key) {
        SharedPreferences store = store();
        String kept = store == null ? null : store.getString("accu:" + key, null);
        try {
            return kept == null ? null : new JSONObject(kept);
        } catch (JSONException e) {
            return null;
        }
    }

    static void rememberSupplierCity(String key, JSONObject place) {
        SharedPreferences store = store();
        if (store != null) store.edit().putString("accu:" + key, place.toString()).apply();
    }

    /** The app's own name for the place where the phone is. */
    static String currentLocation() {
        Context context = Weather.context();
        if (context != null) {
            int id = context.getResources().getIdentifier("current_city", "string", context.getPackageName());
            if (id != 0) return context.getString(id);
        }
        return "Current location";
    }

    static String language() {
        String language = Locale.getDefault().getLanguage();
        return language == null || language.isEmpty() ? "en" : language;
    }
}
