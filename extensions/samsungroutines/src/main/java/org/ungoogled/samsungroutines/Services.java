package org.ungoogled.samsungroutines;

import android.app.SemStatusBarManager;
import android.content.Context;

import com.samsung.android.app.SemExecutableManager;
import com.samsung.android.location.SemLocationManager;
import com.samsung.android.sepunion.SemCustomDumpManager;
import com.samsung.android.sepunion.SemEventDelegationManager;
import com.samsung.android.wifi.SemWifiManager;

/**
 * Every Context.getSystemService(String) in Modes and Routines comes here. Off One UI Samsung's own services are absent
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
            case "sec_location":
                return new SemLocationManager();
            case "sem_wifi":
                return new SemWifiManager();
            case "semeventdelegator":
                return new SemEventDelegationManager(context);
            case "semcustomdump":
                return new SemCustomDumpManager();
            case "execute":
                // App shortcuts: Samsung's own listing, queried by reflection, which then finds nothing.
                return new SemExecutableManager();
        }
        return null;
    }
}
