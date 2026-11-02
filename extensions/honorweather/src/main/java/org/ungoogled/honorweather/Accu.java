package org.ungoogled.honorweather;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Honor Weather's international supplier speaks AccuWeather's API (as Honor's partner serves it): the app asks
 * "&lt;service url&gt;/currentconditions/v1/&lt;key&gt;.json", ".../forecasts/v1/daily/10day/&lt;key&gt;" and so on, and
 * its own parser fills the weather the UI shows. The port's supplier has the service url [BASE], which never leaves
 * the phone: every such request is answered here from Open-Meteo, in AccuWeather's format, so the app's parser and
 * screens work unchanged. What Open-Meteo has no data for (official alerts, life indices, typhoons, tides, minute rain,
 * moon rise and set) is answered empty, which the app shows as "no data".
 * <p>
 * Units as AccuWeather's metric answers: Celsius (unit type 17), km/h (7), km (6), hPa (14), mm (3). Weather icons are
 * AccuWeather's (the app's own icon set), from Open-Meteo's WMO weather codes; texts are the app's own strings.
 */
final class Accu {
    /** The service url of the port's supplier: a reserved name (RFC 2606), so nothing ever resolves it. */
    static final String BASE = "https://open-meteo.invalid";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private Accu() {
    }

    static boolean ours(String url) {
        return url != null && url.startsWith(BASE + "/");
    }

    /** The JSON text AccuWeather's API would answer for this request, from Open-Meteo. */
    static String answer(String url) throws IOException {
        Uri uri = Uri.parse(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        try {
            if (path.startsWith("/locations/v1/cities/geoposition/search") || path.startsWith("/locations/v1/cpoi/search")) {
                String[] q = String.valueOf(uri.getQueryParameter("q")).split(",");
                if (q.length != 2) throw new IOException("no position in " + path);
                return place(Double.parseDouble(q[0].trim()), Double.parseDouble(q[1].trim())).toString();
            }
            if (path.startsWith("/locations/v1/cpoi/autocomplete") || path.startsWith("/locations/v1/cities/autocomplete")) {
                return search(uri.getQueryParameter("q")).toString();
            }
            if (path.startsWith("/locations/v1/cities/translate")) return "[]";
            if (path.startsWith("/locations/v1/")) {
                String key = key(path);
                double[] p = Places.position(key);
                if (p != null) return place(p[0], p[1]).toString();
                JSONObject city = supplierCity(key);
                return named(key, city.optString("LocalizedName"), city.optString("Region"), city.optString("CountryCode"),
                        city.optString("Country"), city.optString("TimeZone"), city.getDouble("Latitude"), city.getDouble("Longitude")).toString();
            }
            if (path.startsWith("/currentconditions/v1/")) return current(position(path)).toString();
            if (path.startsWith("/forecasts/v1/daily/")) return daily(position(path)).toString();
            if (path.startsWith("/forecasts/v1/hourly/")) return hourly(position(path), 0, 24).toString();
            if (path.startsWith("/customization/v1/hourly/24hour_history/")) return hourly(position(path), 24, 24).toString();
            if (path.startsWith("/airqualityforecast/")) return airForecast(position(path)).toString();
            if (path.startsWith("/airquality/")) return air(position(path)).toString();
        } catch (JSONException | RuntimeException e) {
            throw new IOException("Open-Meteo answer for " + path + " not translated", e);
        }
        // Nothing to say: official alerts, life indices, typhoons, tides, China's minute rain, astronomy, history.
        if (path.startsWith("/alerts/") || path.startsWith("/indices/") || path.startsWith("/typhoon/v1/latest")
                || path.startsWith("/tidal/")) {
            return "[]";
        }
        return "{}";
    }

    /** The key at the end of an AccuWeather path ("/.../om52.52_13.41.json"). */
    private static String key(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.endsWith(".json")) last = last.substring(0, last.length() - 5);
        return Uri.decode(last);
    }

    /** The position of a key: ours, or one of the app's suggested cities (found by name). */
    private static double[] position(String path) throws IOException, JSONException {
        String key = key(path);
        double[] p = Places.position(key);
        if (p != null) return p;
        JSONObject city = supplierCity(key);
        return new double[] {city.getDouble("Latitude"), city.getDouble("Longitude")};
    }

    /**
     * One of the app's suggested cities, which it names by the international supplier's city ids: looked up by its
     * English name at Open-Meteo's geocoding (the result in the city's time zone, else country, else the first), once.
     */
    private static JSONObject supplierCity(String key) throws IOException, JSONException {
        JSONObject known = Places.known(key);
        if (known != null) return known;
        String[] city = Places.suggested(key);
        if (city == null) throw new IOException("not an Open-Meteo place: " + key);
        JSONArray results = OpenMeteo.search(city[1], "en").optJSONArray("results");
        JSONObject best = null;
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || !r.has("latitude")) continue;
            if (best == null) best = r;
            if (!city[2].isEmpty() && city[2].equals(r.optString("timezone"))) {
                best = r;
                break;
            }
            if (!city[3].isEmpty() && city[3].equalsIgnoreCase(r.optString("country"))) best = r;
        }
        if (best == null) throw new IOException("Open-Meteo knows no " + city[1]);
        JSONObject place = new JSONObject().put("Key", key).put("LocalizedName", city[0]).put("Region", best.optString("admin1", ""))
                .put("CountryCode", best.optString("country_code", "")).put("Country", best.optString("country", city[3]))
                .put("TimeZone", best.optString("timezone", city[2]))
                .put("Latitude", Math.round(best.getDouble("latitude") * 100) / 100.0)
                .put("Longitude", Math.round(best.getDouble("longitude") * 100) / 100.0);
        Places.rememberSupplierCity(key, place);
        return place;
    }

    // ---- places ----

    private static JSONObject named(String key, String name, String region, String countryCode, String country, String zone,
                                    double lat, double lon) throws JSONException {
        JSONObject place = new JSONObject();
        place.put("Version", 1).put("Key", key).put("Type", "City").put("Rank", 10);
        place.put("LocalizedName", name).put("EnglishName", name);
        // Country: localized name for display; no English name, so the app's "is this city in the SIM's country" check
        // (meant for its supplier's reverse geocoding) is not applied to names it cannot match anyway.
        place.put("Country", new JSONObject().put("ID", countryCode).put("LocalizedName", country).put("EnglishName", ""));
        place.put("AdministrativeArea", new JSONObject().put("ID", "").put("LocalizedName", region).put("EnglishName", region)
                .put("Level", 1).put("LocalizedType", "").put("EnglishType", ""));
        place.put("TimeZone", new JSONObject().put("Name", zone));
        place.put("GeoPosition", new JSONObject().put("Latitude", lat).put("Longitude", lon));
        place.put("SupplementalAdminAreas", new JSONArray());
        place.put("Details", new JSONObject().put("CanonicalLocationKey", key));
        place.put("CityType", 0);
        return place;
    }

    /** The place at a position: a searched (or suggested) city there, else "Current location"; time zone from the forecast. */
    private static JSONObject place(double lat, double lon) throws IOException, JSONException {
        JSONObject searched = Places.named(lat, lon);
        if (searched != null) {
            String key = searched.optString("Key", Places.key(lat, lon));
            double[] p = Places.position(key);
            return named(key, searched.optString("LocalizedName"), searched.optString("Region"), searched.optString("CountryCode"),
                    searched.optString("Country"), searched.optString("TimeZone"), p != null ? p[0] : searched.optDouble("Latitude", lat),
                    p != null ? p[1] : searched.optDouble("Longitude", lon));
        }
        String key = Places.key(lat, lon);
        double[] p = Places.position(key);
        JSONObject forecast = OpenMeteo.forecast(p[0], p[1]);
        return named(key, Places.currentLocation(), "", "", "", forecast.optString("timezone", "UTC"), p[0], p[1]);
    }

    /** City search: Open-Meteo's geocoding, as AccuWeather's autocomplete list. */
    private static JSONArray search(String query) throws IOException, JSONException {
        JSONArray out = new JSONArray();
        if (query == null || query.trim().length() < 2) return out;
        JSONArray results = OpenMeteo.search(query.trim(), Places.language()).optJSONArray("results");
        if (results == null) return out;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || !r.has("latitude") || !r.has("longitude")) continue;
            double lat = Math.round(r.optDouble("latitude") * 100) / 100.0;
            double lon = Math.round(r.optDouble("longitude") * 100) / 100.0;
            String key = Places.key(lat, lon);
            if (!seen.add(key)) continue;
            String region = r.optString("admin1", "");
            JSONObject place = named(key, r.optString("name"), region, r.optString("country_code", ""), r.optString("country", ""),
                    r.optString("timezone", "UTC"), lat, lon);
            JSONObject kept = new JSONObject().put("Key", key).put("LocalizedName", r.optString("name")).put("Region", region)
                    .put("CountryCode", r.optString("country_code", "")).put("Country", r.optString("country", ""))
                    .put("TimeZone", r.optString("timezone", "UTC"));
            Places.remember(key, kept);
            out.put(place);
        }
        return out;
    }

    // ---- weather ----

    /** One WMO weather code as AccuWeather's day icon, night icon and the app's own description. */
    private static final class Kind {
        final int day;
        final int night;
        final String text;
        final String nightText;

        Kind(int day, int night, String text, String nightText) {
            this.day = day;
            this.night = night;
            this.text = text;
            this.nightText = nightText;
        }
    }

    private static Kind kind(int wmo) {
        switch (wmo) {
            case 0: return new Kind(1, 33, "weather_des_sunny", "weather_des_clear");
            case 1: return new Kind(2, 34, "weather_des_mostly_sunny_new", "weather_des_clear");
            case 2: return new Kind(3, 35, "weather_des_partly_sunny", "weather_des_partly_cloudy");
            case 3: return new Kind(7, 7, "weather_des_cloudy", "weather_des_cloudy");
            case 45: case 48: return new Kind(11, 11, "weather_des_fog", "weather_des_fog");
            case 51: case 53: case 55: case 61: return new Kind(18, 18, "weather_des_light_rain", "weather_des_light_rain");
            case 63: return new Kind(18, 18, "weather_des_moderate_rain", "weather_des_moderate_rain");
            case 65: return new Kind(18, 18, "weather_des_heavy_rain", "weather_des_heavy_rain");
            case 56: case 57: case 66: case 67: return new Kind(26, 26, "weather_des_freezing_rain", "weather_des_freezing_rain");
            case 71: return new Kind(19, 19, "weather_des_light_snow", "weather_des_light_snow");
            case 73: return new Kind(22, 22, "weather_des_moderate_snow", "weather_des_moderate_snow");
            case 75: return new Kind(22, 22, "weather_des_heavy_snow", "weather_des_heavy_snow");
            case 77: return new Kind(19, 19, "weather_des_flurries_new", "weather_des_flurries_new");
            case 80: return new Kind(14, 39, "weather_des_showers", "weather_des_showers");
            case 81: case 82: return new Kind(12, 12, "weather_des_showers", "weather_des_showers");
            case 85: return new Kind(21, 43, "weather_des_flurries_new", "weather_des_flurries_new");
            case 86: return new Kind(22, 22, "weather_des_snow", "weather_des_snow");
            case 95: return new Kind(15, 15, "weather_des_thunder_storms", "weather_des_thunder_storms");
            case 96: case 99: return new Kind(15, 15, "weather_des_thunder_shower_with_hail", "weather_des_thunder_shower_with_hail");
            default: return new Kind(7, 7, "weather_des_cloudy", "weather_des_cloudy");
        }
    }

    private static int icon(int wmo, boolean day) {
        Kind k = kind(wmo);
        return day ? k.day : k.night;
    }

    private static String text(int wmo, boolean day) {
        Kind k = kind(wmo);
        String name = day ? k.text : k.nightText;
        Context context = Weather.context();
        if (context != null) {
            int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
            if (id != 0) return context.getString(id);
        }
        return name;
    }

    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private static String compass(double degrees) {
        if (Double.isNaN(degrees)) return "";
        return COMPASS[(int) Math.round(((degrees % 360) + 360) % 360 / 45.0) % 8];
    }

    private static JSONObject value(double v, String unit, int type) throws JSONException {
        return new JSONObject().put("Value", round1(v)).put("Unit", unit).put("UnitType", type);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static JSONObject celsius(double c) throws JSONException {
        return value(c, "C", 17);
    }

    private static JSONObject metricImperial(JSONObject metric, JSONObject imperial) throws JSONException {
        return new JSONObject().put("Metric", metric).put("Imperial", imperial);
    }

    private static JSONObject wind(double kmh, double degrees) throws JSONException {
        return new JSONObject().put("Speed", value(kmh, "km/h", 7))
                .put("Direction", new JSONObject().put("Degrees", (int) Math.round(degrees)).put("Localized", compass(degrees))
                        .put("English", compass(degrees)));
    }

    private static String iso(long epochSeconds, ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), zone).format(ISO);
    }

    private static ZoneId zone(JSONObject forecast) {
        try {
            return ZoneId.of(forecast.optString("timezone", "UTC"));
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }

    private static double num(JSONArray a, int i) {
        return a == null || i < 0 || i >= a.length() || a.isNull(i) ? Double.NaN : a.optDouble(i, Double.NaN);
    }

    private static double or(double v, double fallback) {
        return Double.isNaN(v) ? fallback : v;
    }

    /** AccuWeather's "current conditions" (a one-element list). */
    private static JSONArray current(double[] p) throws IOException, JSONException {
        JSONObject forecast = OpenMeteo.forecast(p[0], p[1]);
        JSONObject c = forecast.getJSONObject("current");
        ZoneId zone = zone(forecast);
        boolean day = c.optInt("is_day", 1) == 1;
        int wmo = c.optInt("weather_code", 3);
        double t = c.optDouble("temperature_2m");
        JSONObject now = new JSONObject();
        long time = c.optLong("time", System.currentTimeMillis() / 1000);
        now.put("LocalObservationDateTime", iso(time, zone)).put("EpochTime", time);
        now.put("WeatherText", text(wmo, day)).put("WeatherIcon", icon(wmo, day)).put("IsDayTime", day);
        now.put("HasPrecipitation", c.optDouble("precipitation", 0) > 0);
        now.put("Temperature", metricImperial(celsius(t), value(t * 1.8 + 32, "F", 18)));
        double feel = c.optDouble("apparent_temperature", t);
        now.put("RealFeelTemperature", metricImperial(celsius(feel), value(feel * 1.8 + 32, "F", 18)));
        double dew = c.optDouble("dew_point_2m", Double.NaN);
        if (!Double.isNaN(dew)) now.put("DewPoint", metricImperial(celsius(dew), value(dew * 1.8 + 32, "F", 18)));
        now.put("RelativeHumidity", c.optInt("relative_humidity_2m"));
        double kmh = c.optDouble("wind_speed_10m", 0);
        JSONObject wind = wind(kmh, c.optDouble("wind_direction_10m", Double.NaN));
        wind.put("Speed", metricImperial(value(kmh, "km/h", 7), value(kmh / 1.609344, "mi/h", 9)));
        now.put("Wind", wind);
        now.put("UVIndex", (int) Math.round(c.optDouble("uv_index", 0)));
        double km = c.optDouble("visibility", Double.NaN) / 1000.0;
        if (!Double.isNaN(km)) now.put("Visibility", metricImperial(value(km, "km", 6), value(km / 1.609344, "mi", 2)));
        now.put("CloudCover", c.optInt("cloud_cover"));
        double hpa = c.optDouble("pressure_msl", Double.NaN);
        if (!Double.isNaN(hpa)) now.put("Pressure", metricImperial(value(hpa, "mb", 14), value(hpa * 0.02953, "inHg", 12)));
        now.put("MobileLink", "").put("Link", "");
        return new JSONArray().put(now);
    }

    /** First hourly index at or after the given epoch second. */
    private static int hourAt(JSONArray times, long epoch) {
        for (int i = 0; i < times.length(); i++) {
            if (times.optLong(i) >= epoch) return i;
        }
        return times.length();
    }

    /** AccuWeather's hourly list: [before] hours back from the current hour, then [after] hours from the next one. */
    private static JSONArray hourly(double[] p, int before, int after) throws IOException, JSONException {
        JSONObject forecast = OpenMeteo.forecast(p[0], p[1]);
        JSONObject h = forecast.getJSONObject("hourly");
        ZoneId zone = zone(forecast);
        JSONArray times = h.getJSONArray("time");
        long now = System.currentTimeMillis() / 1000;
        int next = hourAt(times, now);
        JSONArray out = new JSONArray();
        for (int i = Math.max(0, next - before); i < Math.min(times.length(), next + after); i++) {
            out.put(hour(h, i, zone));
        }
        return out;
    }

    private static JSONObject hour(JSONObject h, int i, ZoneId zone) throws JSONException {
        long time = h.getJSONArray("time").optLong(i);
        boolean day = num(h.optJSONArray("is_day"), i) != 0;
        int wmo = (int) or(num(h.optJSONArray("weather_code"), i), 3);
        double t = num(h.optJSONArray("temperature_2m"), i);
        JSONObject hour = new JSONObject();
        hour.put("DateTime", iso(time, zone)).put("EpochDateTime", time);
        hour.put("WeatherIcon", icon(wmo, day)).put("IconPhrase", text(wmo, day)).put("IsDaylight", day);
        hour.put("Temperature", celsius(t));
        hour.put("RealFeelTemperature", celsius(or(num(h.optJSONArray("apparent_temperature"), i), t)));
        double dew = num(h.optJSONArray("dew_point_2m"), i);
        if (!Double.isNaN(dew)) hour.put("DewPoint", celsius(dew));
        hour.put("Wind", wind(or(num(h.optJSONArray("wind_speed_10m"), i), 0), num(h.optJSONArray("wind_direction_10m"), i)));
        hour.put("RelativeHumidity", (int) Math.round(or(num(h.optJSONArray("relative_humidity_2m"), i), 0)));
        double km = num(h.optJSONArray("visibility"), i) / 1000.0;
        if (!Double.isNaN(km)) hour.put("Visibility", value(km, "km", 6));
        hour.put("UVIndex", (int) Math.round(or(num(h.optJSONArray("uv_index"), i), 0)));
        int chance = (int) Math.round(or(num(h.optJSONArray("precipitation_probability"), i), 0));
        hour.put("PrecipitationProbability", chance).put("RainProbability", chance);
        hour.put("CloudCover", (int) Math.round(or(num(h.optJSONArray("cloud_cover"), i), 0)));
        double mm = or(num(h.optJSONArray("precipitation"), i), 0);
        hour.put("TotalLiquid", value(mm, "mm", 3)).put("Rain", value(or(num(h.optJSONArray("rain"), i), 0), "mm", 3));
        double hpa = num(h.optJSONArray("pressure_msl"), i);
        if (!Double.isNaN(hpa)) hour.put("LocalSource", new JSONObject().put("Pressure", round1(hpa)));
        hour.put("MobileLink", "").put("Link", "");
        return hour;
    }

    /** Moon phase name of a day (AccuWeather's names), from the mean synodic month. */
    private static String moonPhase(long epochSeconds) {
        double days = (epochSeconds - 947182440L) / 86400.0; // new moon 2000-01-06 18:14 UTC
        double age = ((days % 29.530588853) + 29.530588853) % 29.530588853;
        String[] names = {"New", "WaxingCrescent", "First", "WaxingGibbous", "Full", "WaningGibbous", "Last", "WaningCrescent"};
        return names[(int) Math.floor(age / 29.530588853 * 8 + 0.5) % 8];
    }

    /** A day or night half of a forecast day from its hours (AccuWeather: 7:00-19:00 and 19:00-7:00). */
    private static JSONObject half(JSONObject h, long from, long to, int fallbackWmo, boolean day) throws JSONException {
        JSONArray times = h.getJSONArray("time");
        int worst = -1;
        double chance = 0;
        double mm = 0;
        double rain = 0;
        double kmh = 0;
        double direction = Double.NaN;
        for (int i = 0; i < times.length(); i++) {
            long t = times.optLong(i);
            if (t < from || t >= to) continue;
            int wmo = (int) or(num(h.optJSONArray("weather_code"), i), -1);
            worst = Math.max(worst, wmo);
            chance = Math.max(chance, or(num(h.optJSONArray("precipitation_probability"), i), 0));
            mm += or(num(h.optJSONArray("precipitation"), i), 0);
            rain += or(num(h.optJSONArray("rain"), i), 0);
            double w = or(num(h.optJSONArray("wind_speed_10m"), i), 0);
            if (w >= kmh) {
                kmh = w;
                direction = num(h.optJSONArray("wind_direction_10m"), i);
            }
        }
        int wmo = worst < 0 ? fallbackWmo : worst;
        JSONObject half = new JSONObject();
        half.put("Icon", icon(wmo, day)).put("IconPhrase", text(wmo, day)).put("ShortPhrase", text(wmo, day))
                .put("LongPhrase", text(wmo, day));
        half.put("HasPrecipitation", mm > 0);
        half.put("PrecipitationProbability", (int) Math.round(chance)).put("RainProbability", (int) Math.round(chance));
        half.put("Wind", wind(kmh, direction));
        half.put("TotalLiquid", value(mm, "mm", 3)).put("Rain", value(rain, "mm", 3));
        return half;
    }

    /** Highest hourly US AQI of a day, or -1. */
    private static int dayAqi(JSONObject air, long from, long to) {
        if (air == null) return -1;
        JSONObject h = air.optJSONObject("hourly");
        JSONArray times = h == null ? null : h.optJSONArray("time");
        if (times == null) return -1;
        int max = -1;
        for (int i = 0; i < times.length(); i++) {
            long t = times.optLong(i);
            if (t >= from && t < to) max = Math.max(max, (int) Math.round(or(num(h.optJSONArray("us_aqi"), i), -1)));
        }
        return max;
    }

    private static JSONObject airOrNull(double[] p) {
        try {
            return OpenMeteo.air(p[0], p[1]);
        } catch (IOException e) {
            return null;
        }
    }

    /** AccuWeather's daily forecast, from today (Open-Meteo: 16 days). */
    private static JSONObject daily(double[] p) throws IOException, JSONException {
        JSONObject forecast = OpenMeteo.forecast(p[0], p[1]);
        JSONObject d = forecast.getJSONObject("daily");
        JSONObject h = forecast.getJSONObject("hourly");
        JSONObject air = airOrNull(p);
        ZoneId zone = zone(forecast);
        JSONArray days = d.getJSONArray("time");
        long now = System.currentTimeMillis() / 1000;
        JSONArray out = new JSONArray();
        for (int i = 0; i < days.length(); i++) {
            long midnight = days.optLong(i);
            if (midnight + 86400 <= now) continue; // yesterday
            long seven = ZonedDateTime.ofInstant(Instant.ofEpochSecond(midnight), zone).plusHours(7).toEpochSecond();
            int wmo = (int) or(num(d.optJSONArray("weather_code"), i), 3);
            double min = num(d.optJSONArray("temperature_2m_min"), i);
            double max = num(d.optJSONArray("temperature_2m_max"), i);
            if (Double.isNaN(min) || Double.isNaN(max)) continue;
            JSONObject day = new JSONObject();
            day.put("Date", iso(seven, zone)).put("EpochDate", seven);
            long rise = (long) or(num(d.optJSONArray("sunrise"), i), 0);
            long set = (long) or(num(d.optJSONArray("sunset"), i), 0);
            day.put("Sun", new JSONObject().put("Rise", rise == 0 ? "" : iso(rise, zone)).put("EpochRise", rise)
                    .put("Set", set == 0 ? "" : iso(set, zone)).put("EpochSet", set));
            day.put("Moon", new JSONObject().put("EpochRise", 0).put("EpochSet", 0).put("Phase", moonPhase(seven + 5 * 3600)));
            day.put("Temperature", new JSONObject().put("Minimum", celsius(min)).put("Maximum", celsius(max)));
            day.put("RealFeelTemperature", new JSONObject()
                    .put("Minimum", celsius(or(num(d.optJSONArray("apparent_temperature_min"), i), min)))
                    .put("Maximum", celsius(or(num(d.optJSONArray("apparent_temperature_max"), i), max))));
            double daylight = num(d.optJSONArray("daylight_duration"), i);
            if (!Double.isNaN(daylight)) day.put("HoursOfSun", round1(daylight / 3600.0));
            JSONArray pollen = new JSONArray();
            int aqi = dayAqi(air, midnight, midnight + 86400);
            if (aqi >= 0) pollen.put(new JSONObject().put("Name", "AirQuality").put("Value", aqi).put("Category", "").put("CategoryValue", 0));
            double uv = num(d.optJSONArray("uv_index_max"), i);
            if (!Double.isNaN(uv)) pollen.put(new JSONObject().put("Name", "UVIndex").put("Value", (int) Math.round(uv)).put("Category", ""));
            day.put("AirAndPollen", pollen);
            day.put("Day", half(h, seven, seven + 12 * 3600, wmo, true));
            day.put("Night", half(h, seven + 12 * 3600, seven + 24 * 3600, wmo, false));
            day.put("MobileLink", "").put("Link", "");
            out.put(day);
        }
        return new JSONObject().put("Headline", new JSONObject().put("Text", "").put("MobileLink", "").put("Link", ""))
                .put("DailyForecasts", out);
    }

    /** AccuWeather's air quality now: US AQI and pollutants (carbon monoxide in mg/m3, the others in micrograms). */
    private static JSONObject air(double[] p) throws IOException, JSONException {
        JSONObject c = OpenMeteo.air(p[0], p[1]).optJSONObject("current");
        JSONObject out = new JSONObject();
        if (c == null || c.isNull("us_aqi")) return out;
        out.put("Index", c.optInt("us_aqi"));
        out.put("ParticulateMatter2_5", round1(c.optDouble("pm2_5", -1)));
        out.put("ParticulateMatter10", round1(c.optDouble("pm10", -1)));
        out.put("Ozone", round1(c.optDouble("ozone", -1)));
        out.put("NitrogenDioxide", round1(c.optDouble("nitrogen_dioxide", -1)));
        out.put("SulfurDioxide", round1(c.optDouble("sulphur_dioxide", -1)));
        double co = c.optDouble("carbon_monoxide", Double.NaN);
        out.put("CarbonMonoxide", Double.isNaN(co) ? -1 : Math.round(co / 100.0) / 10.0);
        out.put("Source", "Open-Meteo");
        return out;
    }

    /** AccuWeather's hourly air quality forecast, matched to the hourly list by its DateTime. */
    private static JSONObject airForecast(double[] p) throws IOException, JSONException {
        JSONObject forecast = OpenMeteo.forecast(p[0], p[1]);
        ZoneId zone = zone(forecast);
        JSONObject h = OpenMeteo.air(p[0], p[1]).optJSONObject("hourly");
        JSONArray out = new JSONArray();
        JSONArray times = h == null ? null : h.optJSONArray("time");
        if (times != null) {
            for (int i = 0; i < times.length(); i++) {
                double aqi = num(h.optJSONArray("us_aqi"), i);
                if (Double.isNaN(aqi)) continue;
                out.put(new JSONObject().put("StartDateTime", iso(times.optLong(i), zone)).put("AirQuality", String.valueOf(Math.round(aqi))));
            }
        }
        return new JSONObject().put("Forecasts", out);
    }

    static List<String> hosts() {
        return java.util.Arrays.asList(OpenMeteo.FORECAST_HOST, OpenMeteo.AIR_HOST, OpenMeteo.GEOCODING_HOST);
    }
}
