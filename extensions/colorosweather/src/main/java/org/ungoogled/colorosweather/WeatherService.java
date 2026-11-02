package org.ungoogled.colorosweather;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * ColorOS's separate Weather Service app (com.coloros.weather.service). Weather carries the same service itself and
 * uses it when the app is missing.
 */
public final class WeatherService {
    private static final String PACKAGE = "com.coloros.weather.service";

    private WeatherService() {
    }

    /** Settings and units take Weather's ColorOS 13+ path (its own storage) instead of the separate app. */
    public static boolean builtIn() {
        return true;
    }

    /**
     * Weather's own "is the Weather Service app switched off?" check (WeatherServiceUtils.isWeatherServiceDisabled),
     * except that a phone without the app is not asked to enable it.
     */
    public static boolean switchedOff(Context context) {
        if (context != null) {
            try {
                context.getPackageManager().getApplicationInfo(PACKAGE, 0);
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        try {
            Object disabled = Class.forName("com.oplus.weather.utils.WeatherServiceUtils")
                .getMethod("isWeatherServiceDisabled").invoke(null);
            return Boolean.TRUE.equals(disabled);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
