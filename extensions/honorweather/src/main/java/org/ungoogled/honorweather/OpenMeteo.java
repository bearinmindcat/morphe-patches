package org.ungoogled.honorweather;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Open-Meteo (open-meteo.com: free, no account or key), the only service the port talks to: the forecast API, the
 * air-quality API and the geocoding API. Coordinates go out rounded to two decimals (about 1 km); a city search sends
 * the typed name. Nothing is sent before the user allowed it (Consent). Answers are kept for two minutes, so the
 * several requests of one refresh share one download.
 */
final class OpenMeteo {
    static final String FORECAST_HOST = "api.open-meteo.com";
    static final String AIR_HOST = "air-quality-api.open-meteo.com";
    static final String GEOCODING_HOST = "geocoding-api.open-meteo.com";

    private static final long KEEP_MS = 2 * 60 * 1000L;

    private static final String CURRENT = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,"
            + "weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,visibility,uv_index,dew_point_2m";
    private static final String HOURLY = "temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature,"
            + "precipitation_probability,precipitation,rain,weather_code,pressure_msl,cloud_cover,visibility,wind_speed_10m,"
            + "wind_direction_10m,uv_index,is_day";
    private static final String DAILY = "weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,"
            + "apparent_temperature_min,sunrise,sunset,daylight_duration,uv_index_max,precipitation_sum,"
            + "precipitation_probability_max,wind_speed_10m_max,wind_direction_10m_dominant";

    private static final class Kept {
        final long at;
        final JSONObject json;

        Kept(long at, JSONObject json) {
            this.at = at;
            this.json = json;
        }
    }

    private static final Map<String, Kept> KEPT = new HashMap<>();
    private static final Map<String, Object> LOCKS = new HashMap<>();

    private OpenMeteo() {
    }

    /** Coordinates as sent: two decimals. */
    static String coordinate(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Forecast (current, 16 days hourly and daily, yesterday too) for the place, in its own time zone. */
    static JSONObject forecast(double lat, double lon) throws IOException {
        return get("https://" + FORECAST_HOST + "/v1/forecast?latitude=" + coordinate(lat) + "&longitude=" + coordinate(lon)
                + "&current=" + CURRENT + "&hourly=" + HOURLY + "&daily=" + DAILY
                + "&timezone=auto&timeformat=unixtime&wind_speed_unit=kmh&forecast_days=16&past_days=1");
    }

    /** Air quality now and hourly (US AQI and pollutants, micrograms per cubic metre). */
    static JSONObject air(double lat, double lon) throws IOException {
        return get("https://" + AIR_HOST + "/v1/air-quality?latitude=" + coordinate(lat) + "&longitude=" + coordinate(lon)
                + "&current=us_aqi,pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,sulphur_dioxide,ozone"
                + "&hourly=us_aqi&timezone=auto&timeformat=unixtime&forecast_days=5&past_days=1");
    }

    /** Places named like the query, in the phone's language. */
    static JSONObject search(String name, String language) throws IOException {
        return get("https://" + GEOCODING_HOST + "/v1/search?name=" + URLEncoder.encode(name, "UTF-8") + "&count=20&language="
                + URLEncoder.encode(language, "UTF-8") + "&format=json");
    }

    private static Object lock(String url) {
        synchronized (LOCKS) {
            Object lock = LOCKS.get(url);
            if (lock == null) {
                lock = new Object();
                LOCKS.put(url, lock);
            }
            return lock;
        }
    }

    private static JSONObject get(String url) throws IOException {
        if (!Consent.allowed()) {
            Consent.unavailable();
            throw new IOException("Open-Meteo: not allowed by the user");
        }
        synchronized (lock(url)) {
            synchronized (KEPT) {
                Kept kept = KEPT.get(url);
                if (kept != null && System.currentTimeMillis() - kept.at < KEEP_MS) return kept.json;
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/json");
            try {
                int code = connection.getResponseCode();
                InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String body = in == null ? "" : read(in);
                if (code != 200) throw new IOException("Open-Meteo HTTP " + code + ": " + body);
                JSONObject json = new JSONObject(body);
                if (json.optBoolean("error")) throw new IOException("Open-Meteo: " + json.optString("reason"));
                synchronized (KEPT) {
                    KEPT.put(url, new Kept(System.currentTimeMillis(), json));
                }
                return json;
            } catch (JSONException e) {
                throw new IOException("Open-Meteo: unreadable answer", e);
            } finally {
                connection.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            for (int n; (n = stream.read(buffer)) > 0; ) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
