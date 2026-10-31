package org.ungoogled.samsungvoicerecorder;

import android.app.SemStatusBarManager;
import android.content.Context;

import com.samsung.android.knox.SemPersonaManager;
import com.samsung.android.knox.SemRemoteContentManager;

/**
 * Every Context.getSystemService(String) in Voice Recorder comes here. Off One UI Samsung's own services are absent
 * (null); the ones it uses without checking get stand-ins that do nothing.
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
            case "persona":
                return new SemPersonaManager();
            case "rcp":
                return new SemRemoteContentManager();
        }
        return null;
    }
}
