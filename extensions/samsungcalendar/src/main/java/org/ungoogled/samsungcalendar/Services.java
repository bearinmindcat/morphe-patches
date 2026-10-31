package org.ungoogled.samsungcalendar;

import android.app.SemStatusBarManager;
import android.content.Context;

import com.samsung.android.content.clipboard.SemClipboardManager;
import com.samsung.android.desktopmode.SemDesktopModeManager;
import com.samsung.android.gesture.SemMotionRecognitionManager;

/**
 * Every Context.getSystemService(String) in Calendar comes here. Off One UI Samsung's own services are absent (null);
 * the ones Calendar uses without checking get a stand-in that does nothing (the full-screen alert first, through
 * "sem_statusbar").
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
            case "semclipboard":
                return new SemClipboardManager();
            case "motion_recognition":
                return new SemMotionRecognitionManager();
            case "desktopmode":
                return new SemDesktopModeManager();
        }
        return null;
    }
}
