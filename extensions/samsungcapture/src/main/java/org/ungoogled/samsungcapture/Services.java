package org.ungoogled.samsungcapture;

import android.app.SemStatusBarManager;
import android.content.Context;

/**
 * Every Context.getSystemService(String) in Samsung capture comes here. Off One UI Samsung's own services are absent
 * (null); the status bar one gets a stand-in that does nothing (an app may not close the notification shade).
 */
public final class Services {
    private static volatile Context app;

    private Services() {
    }

    /** The app's context, from the first service lookup (stand-ins that need one have no other way to it). */
    public static Context app() {
        return app;
    }

    public static Object get(Context context, String name) {
        if (app == null && context != null) app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Object service = context.getSystemService(name);
        if (service != null || name == null) return service;
        if ("sem_statusbar".equals(name)) return new SemStatusBarManager();
        return null;
    }
}
