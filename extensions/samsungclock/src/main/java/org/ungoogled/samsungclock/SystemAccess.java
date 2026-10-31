package org.ungoogled.samsungclock;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.Display;
import android.provider.Settings;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/**
 * Calls Samsung's own Clock makes with system privileges, made the ordinary app's way. Its alarm and timer pop-ups over
 * other apps are system windows (keyguard dialog): they open as ordinary overlays where "Display over other apps" is
 * allowed; otherwise Android refuses them, the ringing notification takes their place, and their later updates and
 * removal are skipped. Listening for calls needs the phone permission (privileged for Samsung's copy): refused, the
 * call state comes from the audio mode instead.
 */
public final class SystemAccess {
    private static final String TAG = "UA";
    private static final Set<View> REFUSED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private SystemAccess() {
    }

    // TelephonyManager

    public static void registerTelephonyCallback(TelephonyManager tm, Executor executor, TelephonyCallback callback) {
        try {
            tm.registerTelephonyCallback(executor, callback);
        } catch (SecurityException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "call listener refused: " + e.getMessage());
        }
    }

    public static int getCallState(TelephonyManager tm) {
        try {
            return tm.getCallState();
        } catch (SecurityException e) {
            Context c = AlertScreen.application();
            AudioManager audio = c == null ? null : c.getSystemService(AudioManager.class);
            if (audio == null) return TelephonyManager.CALL_STATE_IDLE;
            switch (audio.getMode()) {
                case AudioManager.MODE_RINGTONE:
                    return TelephonyManager.CALL_STATE_RINGING;
                case AudioManager.MODE_IN_CALL:
                case AudioManager.MODE_IN_COMMUNICATION:
                    return TelephonyManager.CALL_STATE_OFFHOOK;
                default:
                    return TelephonyManager.CALL_STATE_IDLE;
            }
        }
    }

    // Context

    /** The pop-ups' window context carries their window type, which must match the overlay they become. */
    public static Context createWindowContext(Context c, int type, Bundle options) {
        return c.createWindowContext(overlay(c, type), options);
    }

    public static Context createWindowContext(Context c, Display display, int type, Bundle options) {
        return c.createWindowContext(display, overlay(c, type), options);
    }

    // System

    /** Loading one of Samsung's system libraries (the Quram codecs) is no longer fatal; their natives answer nothing. */
    public static void loadLibrary(String name) {
        try {
            System.loadLibrary(name);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "native library not available: " + name);
        }
    }

    // ViewManager (WindowManager)

    /** A window type only the system may use: 2000 and up, but not the ordinary overlay (2038). */
    private static boolean systemType(int type) {
        return type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW && type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    /** A system window type as the ordinary overlay, where the app may draw over others. */
    private static int overlay(Context c, int type) {
        return systemType(type) && Settings.canDrawOverlays(c) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : type;
    }

    private static void asOverlay(View view, ViewGroup.LayoutParams params) {
        if (params instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) params;
            lp.type = overlay(view.getContext(), lp.type);
        }
    }

    public static void addView(ViewManager wm, View view, ViewGroup.LayoutParams params) {
        asOverlay(view, params);
        try {
            wm.addView(view, params);
            REFUSED.remove(view);
        } catch (WindowManager.BadTokenException | SecurityException | IllegalArgumentException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "window refused: " + e.getMessage());
            REFUSED.add(view);
        }
    }


    public static void updateViewLayout(ViewManager wm, View view, ViewGroup.LayoutParams params) {
        asOverlay(view, params);
        if (!REFUSED.contains(view)) wm.updateViewLayout(view, params);
    }


    public static void removeView(ViewManager wm, View view) {
        if (!REFUSED.remove(view)) wm.removeView(view);
    }


    public static void removeViewImmediate(WindowManager wm, View view) {
        if (!REFUSED.remove(view)) wm.removeViewImmediate(view);
    }
}
