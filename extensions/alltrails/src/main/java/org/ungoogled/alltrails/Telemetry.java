package org.ungoogled.alltrails;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Switches AllTrails' tracking SDKs off as the app starts (Remove telemetry).
 * The SDKs are called through their public APIs by reflection, so a missing or
 * renamed SDK in another AllTrails version only skips that one step.
 */
public final class Telemetry {
    private Telemetry() {}

    /** AllTrailsApplication.onCreate, right after super.onCreate and before AllTrails' own start-up work. */
    public static void off(Context context) {
        off(context, false);
    }

    /** The same, with the "Keep push notifications" option: Braze stays online. */
    public static void offKeepBraze(Context context) {
        off(context, true);
    }

    private static void off(Context context, boolean keepBraze) {
        // Braze: no network at all (what AllTrails itself does on a satellite connection).
        if (!keepBraze) try {
            Class<?> braze = Class.forName("com.braze.Braze");
            Class<?> companionType = Class.forName("com.braze.Braze$Companion");
            for (Field field : braze.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == companionType) {
                    field.setAccessible(true);
                    Object companion = field.get(null);
                    companionType.getMethod("setOutboundNetworkRequestsOffline", boolean.class).invoke(companion, true);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }

        // Mapbox: its own telemetry switch (map and location events); only its usage ping stays.
        try {
            Class<?> callback = Class.forName("com.mapbox.common.TelemetryUtilsResponseCallback");
            Class.forName("com.mapbox.common.TelemetryUtils")
                .getMethod("setEventsCollectionState", boolean.class, callback)
                .invoke(null, false, null);
        } catch (Throwable ignored) {
        }
    }
}
