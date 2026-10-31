package org.ungoogled.samsungclock;

import android.app.SemStatusBarManager;
import android.content.Context;

import com.samsung.android.gesture.SemMotionRecognitionManager;

/**
 * Every Context.getSystemService(String) in Clock comes here. Off One UI Samsung's own services are absent (null);
 * the ones Clock uses without checking (the status bar's, as an alarm pops up, and motion gestures) get stand-ins
 * that do nothing.
 */
public final class Services {
    private Services() {
    }

    public static Object get(Context context, String name) {
        Object service = context.getSystemService(name);
        if (service != null || name == null) return service;
        switch (name) {
            case "sem_statusbar":
                return new SemStatusBarManager();
            case "motion_recognition":
                return new SemMotionRecognitionManager();
        }
        return null;
    }
}
