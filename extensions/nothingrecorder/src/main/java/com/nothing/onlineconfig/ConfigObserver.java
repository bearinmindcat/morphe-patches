package com.nothing.onlineconfig;

import android.content.Context;
import android.os.Handler;

/**
 * Stand-in for Nothing OS's online-config change observer. Off a Nothing phone there is nothing to observe, so this
 * never calls its ConfigUpdater back and register/unregister do nothing. The nested ConfigUpdater interface is found
 * by name via reflection.
 */
public final class ConfigObserver {
    public interface ConfigUpdater {
        void updateConfig(org.json.JSONArray config);
    }

    public ConfigObserver(Context context, Handler handler, ConfigUpdater updater, String name) {
    }

    public void register() {
    }

    public void unregister() {
    }
}
