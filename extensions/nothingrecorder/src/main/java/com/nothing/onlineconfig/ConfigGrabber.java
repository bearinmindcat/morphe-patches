package com.nothing.onlineconfig;

import android.content.Context;

import org.json.JSONArray;

/**
 * Stand-in for Nothing OS's online (server) feature-config reader. Off a Nothing phone there is no such service, so
 * grabConfig returns null and Nothing Recorder falls back to its own bundled sound_recorder_config.
 */
public final class ConfigGrabber {
    public ConfigGrabber(Context context, String name) {
    }

    public JSONArray grabConfig() {
        return null;
    }
}
