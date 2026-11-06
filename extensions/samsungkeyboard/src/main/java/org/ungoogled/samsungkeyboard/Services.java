package org.ungoogled.samsungkeyboard;

import android.app.SemStatusBarManager;
import android.content.Context;

import com.samsung.android.desktopmode.SemDesktopModeManager;

/**
 * Every Context.getSystemService(String) in the keyboard comes here. Off One UI Samsung's own services are absent
 * (null); the ones it uses without checking (the status bar's, closing the notification shade, and desktop mode) get
 * stand-ins that do nothing, and Samsung's clipboard service is LocalClipboard.
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
            case "desktopmode":
                return new SemDesktopModeManager();
            case "semclipboard":
                return LocalClipboard.get(context);
        }
        return null;
    }
}
