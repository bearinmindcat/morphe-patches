package com.nothing.experience;

import android.content.Context;
import android.os.Bundle;

/**
 * Stand-in for Nothing OS's product-analytics client (the real one is part of the ROM). Off a Nothing phone it is
 * absent and the direct references would be a NoClassDefFoundError. This swallows every event, so nothing is sent.
 */
public final class AppTracking {
    private static AppTracking instance;

    public static synchronized AppTracking getInstance(Context context) {
        if (instance == null) {
            instance = new AppTracking();
        }
        return instance;
    }

    public boolean logProductEvent(String name, Bundle params) {
        return false;
    }

    private AppTracking() {
    }
}
