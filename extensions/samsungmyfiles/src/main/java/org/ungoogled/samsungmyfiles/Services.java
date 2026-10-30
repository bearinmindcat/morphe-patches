package org.ungoogled.samsungmyfiles;

import android.content.Context;

import com.samsung.android.sepunion.SemEventDelegationManager;

/**
 * Every Context.getSystemService(String) in My Files comes here. Off One UI Samsung's own services are absent (null);
 * the one My Files uses without checking gets a stand-in that does nothing.
 */
public final class Services {
    private Services() {
    }

    public static Object get(Context context, String name) {
        Object service = context.getSystemService(name);
        if (service != null || name == null) return service;
        switch (name) {
            case "semeventdelegator":
                return new SemEventDelegationManager();
        }
        return null;
    }
}
