package org.ungoogled.xiaomilauncher;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;

/**
 * Widget binding. The HyperOS launcher binds widgets with the BIND_APPWIDGET permission a system home screen holds; any other
 * app needs the user's "Always allow" in Android's own widget dialog once. Where a bind is refused, this opens that
 * dialog (WidgetPermissionActivity); once allowed, adding a widget works like on a Xiaomi phone. Copied from One UI
 * Home's (extensions/samsunglauncher).
 */
public final class Widgets {
    private static long asked;

    private Widgets() {
    }

    public static boolean bind(AppWidgetManager manager, int id, ComponentName provider) {
        return checked(manager.bindAppWidgetIdIfAllowed(id, provider), provider, null);
    }

    public static boolean bind(AppWidgetManager manager, int id, ComponentName provider, Bundle options) {
        return checked(manager.bindAppWidgetIdIfAllowed(id, provider, options), provider, null);
    }

    public static boolean bind(AppWidgetManager manager, int id, UserHandle user, ComponentName provider, Bundle options) {
        return checked(manager.bindAppWidgetIdIfAllowed(id, user, provider, options), provider, user);
    }

    private static boolean checked(boolean bound, ComponentName provider, UserHandle user) {
        if (bound || provider == null) return bound;
        // Several binds can fail together (a restored layout): one dialog, not one per widget.
        long now = SystemClock.elapsedRealtime();
        if (asked != 0 && now - asked < 10_000) return false;
        asked = now;
        Context c = App.context();
        if (c != null) {
            Intent ask = new Intent(c, WidgetPermissionActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider);
            if (user != null) ask.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user);
            try {
                c.startActivity(ask);
            } catch (RuntimeException e) {
                // No dialog, no widget: as before.
            }
        }
        return false;
    }
}
