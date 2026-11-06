package org.ungoogled.colorosclock;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Home screen widgets are drawn by the launcher, which inflates Clock's layouts with the framework's classes only.
 * Clock picks its OnePlus layouts (red hour, com.oplus.widget.OplusTextClock) when that class loads, and off ColorOS
 * it loads from the stand-in inside Clock: the launcher then can't inflate them ("Can't load widget"). A class counts
 * here only when the framework itself has it, so other phones get Clock's own TextClock layouts.
 *
 * Their weather comes from ColorOS's weather service (com.coloros.weather.service, through the weather service SDK).
 * Where it is not installed the weather would read "--" forever: every widget update then leaves the weather views
 * out (the date and city names next to them stay).
 */
public final class Widgets {
    private static final String WEATHER_SERVICE = "com.coloros.weather.service";
    private static final String[] WEATHER_VIEWS = {
        "iv_weather_type", "local_weather_info_txt", "local_weather_des_txt", "local_weather_info_refresh_container",
        "resident_iv_weather_type", "resident_weather_info_txt", "weather_info",
    };
    private static int[] weatherIds;

    private Widgets() {
    }

    /** Whether the framework (boot class path, which every launcher has) has this class. */
    public static boolean frameworkHas(String name) {
        try {
            Class.forName(name, false, null);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static void updateAppWidget(AppWidgetManager manager, int id, RemoteViews views) {
        manager.updateAppWidget(id, withoutWeather(views));
    }

    public static void updateAppWidget(AppWidgetManager manager, int[] ids, RemoteViews views) {
        manager.updateAppWidget(ids, withoutWeather(views));
    }

    public static void partiallyUpdateAppWidget(AppWidgetManager manager, int id, RemoteViews views) {
        manager.partiallyUpdateAppWidget(id, withoutWeather(views));
    }

    public static void partiallyUpdateAppWidget(AppWidgetManager manager, int[] ids, RemoteViews views) {
        manager.partiallyUpdateAppWidget(ids, withoutWeather(views));
    }

    private static RemoteViews withoutWeather(RemoteViews views) {
        if (views == null) return null;
        try {
            Context context = application();
            if (context == null || !noWeatherService(context)) return views;
            for (int id : weatherIds(context)) views.setViewVisibility(id, View.GONE);
        } catch (RuntimeException e) {
            // RemoteViews with separate layouts can't take more actions: left as Clock made them.
        }
        return views;
    }

    static boolean noWeatherService(Context context) {
        try {
            context.getPackageManager().getPackageInfo(WEATHER_SERVICE, 0);
            return false;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static synchronized int[] weatherIds(Context context) {
        if (weatherIds == null) {
            int[] ids = new int[WEATHER_VIEWS.length];
            int found = 0;
            for (String name : WEATHER_VIEWS) {
                int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
                if (id != 0) ids[found++] = id;
            }
            weatherIds = java.util.Arrays.copyOf(ids, found);
        }
        return weatherIds;
    }

    private static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
