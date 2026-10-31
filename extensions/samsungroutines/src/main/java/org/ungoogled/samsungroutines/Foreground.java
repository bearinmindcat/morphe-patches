package org.ungoogled.samsungroutines;

import android.app.Notification;
import android.app.Service;
import android.content.pm.ServiceInfo;

/**
 * On One UI the app runs as SystemUI, whose foreground services may use the "system exempted" type; any other app needs
 * an exact-alarm grant for it and the loading service crashed the app at its first start. The manifest declares that
 * service "special use" instead, and its startForeground asks for that type.
 */
public final class Foreground {
    private Foreground() {
    }

    public static void startForeground(Service service, int id, Notification notification, int type) {
        if ((type & ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) != 0) {
            type = (type & ~ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        service.startForeground(id, notification, type);
    }
}
