package org.ungoogled.samsunglauncher;

import android.app.SemStatusBarManager;
import android.content.Context;

/**
 * Every Context.getSystemService(String) in One UI Home comes here. Off One UI Samsung's own services are absent
 * (null); the status bar's, which the home screen uses without checking, gets a stand-in.
 */
public final class Services {
    private Services() {
    }

    public static Object get(Context context, String name) {
        Object service = context.getSystemService(name);
        if (service != null || name == null) return service;
        if ("sem_statusbar".equals(name)) return new SemStatusBarManager();
        return null;
    }
}
