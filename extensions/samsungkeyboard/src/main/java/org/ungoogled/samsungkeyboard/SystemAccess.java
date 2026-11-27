package org.ungoogled.samsungkeyboard;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What the installed-beside copy of Samsung Keyboard may not do as an ordinary app, done the ordinary way. As a
 * system app the keyboard may read and write any setting, broadcast to every user and open system windows;
 * refused, it got an exception it never expected and died. Here a refused setting read answers as a missing
 * setting does, a refused write reports failure (the keyboard's own switches kept in system settings are kept by
 * the app instead), a broadcast to every user goes to this one, and a refused window stays unshown.
 */
public final class SystemAccess {
    private static final String TAG = "UA";

    private SystemAccess() {
    }

    private static void refused(String name, RuntimeException e) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "setting refused: " + name + " (" + e.getMessage() + ")");
    }

    /** The one refused read the keyboard depends on: which keyboards are on, as the system lists them. */
    private static String enabledInputMethods() {
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            InputMethodManager imm = (InputMethodManager) app.getSystemService(Context.INPUT_METHOD_SERVICE);
            List<InputMethodInfo> list = imm.getEnabledInputMethodList();
            StringBuilder ids = new StringBuilder();
            for (InputMethodInfo info : list) {
                if (ids.length() > 0) ids.append(':');
                ids.append(info.getId());
            }
            return ids.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String fallback(String name) {
        return Settings.Secure.ENABLED_INPUT_METHODS.equals(name) ? enabledInputMethods() : null;
    }

    // The keyboard's own switches that it keeps in system settings

    /**
     * Key-press sound and vibration: the keyboard's switches write them to system settings and the keyboard plays
     * sound and vibrates from what it reads back there. Android refuses an ordinary app that write, so the switch
     * changed nothing (sound stayed on). Refused, the value is kept in the app's own storage (device-protected, so the
     * keyboard has it before the first unlock) and answers every later read; the keyboard's own observers of the
     * setting are told it changed. A write the system takes clears the kept value.
     */
    private static final Set<String> OWN = new HashSet<>(Arrays.asList(
            "system/sip_key_feedback_sound",
            "system/sip_key_feedback_vibration"));
    private static final String STORE = "org.ungoogled.samsungkeyboard.settings";
    private static SharedPreferences store;

    private static SharedPreferences store() {
        if (store == null) {
            try {
                Application app = (Application) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (app == null) return null;
                store = app.createDeviceProtectedStorageContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return store;
    }

    /** The value kept for one of the keyboard's own settings, or null (not one of them, or never refused). */
    private static String kept(String table, String name) {
        if (name == null || !OWN.contains(table + "/" + name)) return null;
        SharedPreferences prefs = store();
        return prefs != null ? prefs.getString(table + "/" + name, null) : null;
    }

    /** After a write: true when the system took it (any kept value goes), else whether the app kept it. */
    private static boolean keep(ContentResolver r, String table, String name, String value, boolean written) {
        if (name == null || !OWN.contains(table + "/" + name)) return written;
        SharedPreferences prefs = store();
        if (prefs == null) return written;
        if (written) {
            prefs.edit().remove(table + "/" + name).apply();
            return true;
        }
        prefs.edit().putString(table + "/" + name, value).apply();
        try {
            r.notifyChange(Settings.System.getUriFor(name), null);
        } catch (RuntimeException e) {
            refused("notify " + name, e);
        }
        return true;
    }

    private static int keptInt(String kept, int def) {
        try {
            return Integer.parseInt(kept);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long keptLong(String kept, long def) {
        try {
            return Long.parseLong(kept);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float keptFloat(String kept, float def) {
        try {
            return Float.parseFloat(kept);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean systemPut(ContentResolver r, String name, String value) {
        try {
            return Settings.System.putString(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    // Settings.Secure

    public static String secureGetString(ContentResolver r, String name) {
        try {
            return Settings.Secure.getString(r, name);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return fallback(name);
        }
    }

    public static int secureGetInt(ContentResolver r, String name, int def) {
        try {
            return Settings.Secure.getInt(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static int secureGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Settings.Secure.getInt(r, name);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static long secureGetLong(ContentResolver r, String name, long def) {
        try {
            return Settings.Secure.getLong(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static float secureGetFloat(ContentResolver r, String name, float def) {
        try {
            return Settings.Secure.getFloat(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static boolean securePutString(ContentResolver r, String name, String value) {
        try {
            return Settings.Secure.putString(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    public static boolean securePutInt(ContentResolver r, String name, int value) {
        try {
            return Settings.Secure.putInt(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    public static boolean securePutLong(ContentResolver r, String name, long value) {
        try {
            return Settings.Secure.putLong(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    public static boolean securePutFloat(ContentResolver r, String name, float value) {
        try {
            return Settings.Secure.putFloat(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    // Settings.System

    public static String systemGetString(ContentResolver r, String name) {
        String kept = kept("system", name);
        if (kept != null) return kept;
        try {
            return Settings.System.getString(r, name);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return null;
        }
    }

    public static int systemGetInt(ContentResolver r, String name, int def) {
        String kept = kept("system", name);
        if (kept != null) return keptInt(kept, def);
        try {
            return Settings.System.getInt(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static int systemGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        String kept = kept("system", name);
        if (kept != null) {
            try {
                return Integer.parseInt(kept);
            } catch (NumberFormatException e) {
                throw new Settings.SettingNotFoundException(name);
            }
        }
        try {
            return Settings.System.getInt(r, name);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static long systemGetLong(ContentResolver r, String name, long def) {
        String kept = kept("system", name);
        if (kept != null) return keptLong(kept, def);
        try {
            return Settings.System.getLong(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static float systemGetFloat(ContentResolver r, String name, float def) {
        String kept = kept("system", name);
        if (kept != null) return keptFloat(kept, def);
        try {
            return Settings.System.getFloat(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static boolean systemPutString(ContentResolver r, String name, String value) {
        return keep(r, "system", name, value, systemPut(r, name, value));
    }

    public static boolean systemPutInt(ContentResolver r, String name, int value) {
        return keep(r, "system", name, Integer.toString(value), systemPut(r, name, Integer.toString(value)));
    }

    public static boolean systemPutLong(ContentResolver r, String name, long value) {
        return keep(r, "system", name, Long.toString(value), systemPut(r, name, Long.toString(value)));
    }

    public static boolean systemPutFloat(ContentResolver r, String name, float value) {
        return keep(r, "system", name, Float.toString(value), systemPut(r, name, Float.toString(value)));
    }

    private static Method semGetIntForUser;

    /** Samsung's per-user read; the keyboard only ever asks for the current user. */
    public static int systemSemGetIntForUser(ContentResolver r, String name, int def, int user) {
        String kept = kept("system", name);
        if (kept != null) return keptInt(kept, def);
        try {
            if (semGetIntForUser == null) {
                semGetIntForUser = Settings.System.class.getMethod("semGetIntForUser",
                        ContentResolver.class, String.class, int.class, int.class);
            }
            return (Integer) semGetIntForUser.invoke(null, r, name, def, user);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SecurityException || cause instanceof IllegalArgumentException) {
                refused(name, (RuntimeException) cause);
            }
            return systemGetInt(r, name, def);
        }
    }

    // Settings.Global

    public static String globalGetString(ContentResolver r, String name) {
        try {
            return Settings.Global.getString(r, name);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return null;
        }
    }

    public static int globalGetInt(ContentResolver r, String name, int def) {
        try {
            return Settings.Global.getInt(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static int globalGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Settings.Global.getInt(r, name);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static long globalGetLong(ContentResolver r, String name, long def) {
        try {
            return Settings.Global.getLong(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static float globalGetFloat(ContentResolver r, String name, float def) {
        try {
            return Settings.Global.getFloat(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static boolean globalPutString(ContentResolver r, String name, String value) {
        try {
            return Settings.Global.putString(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    public static boolean globalPutInt(ContentResolver r, String name, int value) {
        try {
            return Settings.Global.putInt(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    public static boolean globalPutLong(ContentResolver r, String name, long value) {
        try {
            return Settings.Global.putLong(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    public static boolean globalPutFloat(ContentResolver r, String name, float value) {
        try {
            return Settings.Global.putFloat(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    // WindowManager.LayoutParams.inputFeatures

    /** INPUT_FEATURE_SPY: a window that sees every touch on the screen, for apps holding MONITOR_INPUT only. */
    private static final int INPUT_FEATURE_SPY = 1 << 2;
    private static Boolean monitorInput;

    private static boolean mayMonitorInput() {
        if (monitorInput == null) {
            try {
                Application app = (Application) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                monitorInput = app != null
                        && app.checkSelfPermission("android.permission.MONITOR_INPUT") == PackageManager.PERMISSION_GRANTED;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }
        return monitorInput;
    }

    /**
     * The keyboard turns off "user activity" for its window while emoji, stickers or the clipboard are open, by setting
     * a bit in the window's hidden inputFeatures field. Android hides that bit's constant from apps, and the keyboard's
     * fallback for it, 4, is INPUT_FEATURE_SPY, which needs MONITOR_INPUT (a system keyboard's permission): Android 13
     * and 14 then killed the keyboard at its next layout ("Cannot use INPUT_FEATURE_SPY"), Android 15 refused the
     * layout. The spy bit is dropped unless the app holds that permission; every other field is set as asked.
     */
    public static void fieldSet(Field field, Object target, Object value) throws IllegalAccessException {
        if (target instanceof WindowManager.LayoutParams && value instanceof Integer
                && "inputFeatures".equals(field.getName()) && !mayMonitorInput()) {
            value = (Integer) value & ~INPUT_FEATURE_SPY;
        }
        field.set(target, value);
    }

    // Hardware identifiers

    /*
     * The phone's serial number and IMEI/MEID are never read: the keyboard hashes one of them into its language pack
     * requests to Samsung. Android refuses them to an ordinary app anyway, and Android 13 has no getPrimaryImei at all
     * (the keyboard died on it, "Samsung Keyboard has stopped", as a language download started). Withheld, the request
     * goes without them.
     */

    public static String buildGetSerial() {
        return "";
    }

    public static String telephonyManagerGetPrimaryImei(TelephonyManager manager) {
        return "";
    }

    public static String telephonyManagerGetImei(TelephonyManager manager) {
        return "";
    }

    public static String telephonyManagerGetImei(TelephonyManager manager, int slot) {
        return "";
    }

    public static String telephonyManagerGetMeid(TelephonyManager manager) {
        return "";
    }

    public static String telephonyManagerGetMeid(TelephonyManager manager, int slot) {
        return "";
    }

    public static String telephonyManagerGetDeviceId(TelephonyManager manager) {
        return "";
    }

    public static String telephonyManagerGetDeviceId(TelephonyManager manager, int slot) {
        return "";
    }

    // TelephonyManager

    /**
     * The keyboard listens for calls as it starts, which needs a phone permission only the system keyboard has: refused,
     * it simply does not hear of calls (it stopped starting up halfway before).
     */
    public static void telephonyManagerListen(TelephonyManager manager, PhoneStateListener listener, int events) {
        try {
            manager.listen(listener, events);
        } catch (SecurityException e) {
            refused("phone state", e);
        }
    }

    // Context

    /** The keyboard tells every user which language it is typing; this user is the one that matters. */
    public static void contextSendBroadcastAsUser(Context c, Intent intent, UserHandle user) {
        try {
            c.sendBroadcastAsUser(intent, user);
        } catch (SecurityException e) {
            refused(intent.getAction(), e);
            c.sendBroadcast(intent);
        }
    }

    // ViewManager (WindowManager)

    /** Windows Android refused to add, so their later updates and removal are skipped too. */
    private static final Set<View> REFUSED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** The sticker suggestions' bubble over the text is a system window (input method dialog). */
    public static void viewManagerAddView(ViewManager wm, View view, ViewGroup.LayoutParams params) {
        try {
            wm.addView(view, params);
            REFUSED.remove(view);
        } catch (WindowManager.BadTokenException | SecurityException e) {
            refused(view.getClass().getSimpleName(), e);
            REFUSED.add(view);
        }
    }

    public static void viewManagerUpdateViewLayout(ViewManager wm, View view, ViewGroup.LayoutParams params) {
        if (!REFUSED.contains(view)) wm.updateViewLayout(view, params);
    }

    public static void viewManagerRemoveView(ViewManager wm, View view) {
        if (!REFUSED.remove(view)) wm.removeView(view);
    }

    public static void windowManagerRemoveViewImmediate(WindowManager wm, View view) {
        if (!REFUSED.remove(view)) wm.removeViewImmediate(view);
    }
}
