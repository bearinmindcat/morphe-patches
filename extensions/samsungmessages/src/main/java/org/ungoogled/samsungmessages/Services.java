package org.ungoogled.samsungmessages;

import android.app.SemStatusBarManager;
import android.content.Context;

import com.samsung.android.content.clipboard.SemClipboardManager;
import com.samsung.android.gesture.SemMotionRecognitionManager;
import com.samsung.android.location.SemLocationManager;
import com.samsung.android.telecom.SemTelecomManager;

/**
 * Every Context.getSystemService(String) in Samsung Messages comes here. Off One UI Samsung's own services are absent
 * (null); the ones it uses without checking get a stand-in that does nothing.
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
        }
        return null;
    }

    /** Context.getSystemService(Class): One UI's own managers looked up by class get the same stand-ins. */
    public static Object getSystemService(Context context, Class<?> type) {
        Object service = context.getSystemService(type);
        if (service != null || type == null) return service;
        if (type == SemTelecomManager.class) return new SemTelecomManager();
        if (type == SemLocationManager.class) return new SemLocationManager();
        return null;
    }
}
