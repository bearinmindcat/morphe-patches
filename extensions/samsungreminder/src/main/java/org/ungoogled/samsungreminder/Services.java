package org.ungoogled.samsungreminder;

import android.app.SemStatusBarManager;
import android.content.Context;

import com.samsung.android.gesture.SemMotionRecognitionManager;

/**
 * Every Context.getSystemService(String) in Reminder comes here. Off One UI Samsung's own services are absent (null);
 * the ones Reminder uses without checking (status bar, motion) get a stand-in that does nothing.
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
