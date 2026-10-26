package org.ungoogled.samsungkeyboard;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewManager;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What the installed-beside copy of Samsung Keyboard may not do as an ordinary app, done the ordinary way. As a
 * system app the keyboard may read and write any setting, broadcast to every user and open system windows;
 * refused, it got an exception it never expected and died. Here a refused setting read answers as a missing
 * setting does, a refused write reports failure, a broadcast to every user goes to this one, and a refused window
 * stays unshown.
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
        try {
            return Settings.System.getString(r, name);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return null;
        }
    }

    public static int systemGetInt(ContentResolver r, String name, int def) {
        try {
            return Settings.System.getInt(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static int systemGetInt(ContentResolver r, String name) throws Settings.SettingNotFoundException {
        try {
            return Settings.System.getInt(r, name);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            throw new Settings.SettingNotFoundException(name);
        }
    }

    public static long systemGetLong(ContentResolver r, String name, long def) {
        try {
            return Settings.System.getLong(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static float systemGetFloat(ContentResolver r, String name, float def) {
        try {
            return Settings.System.getFloat(r, name, def);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return def;
        }
    }

    public static boolean systemPutString(ContentResolver r, String name, String value) {
        try {
            return Settings.System.putString(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    public static boolean systemPutInt(ContentResolver r, String name, int value) {
        try {
            return Settings.System.putInt(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    public static boolean systemPutLong(ContentResolver r, String name, long value) {
        try {
            return Settings.System.putLong(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    public static boolean systemPutFloat(ContentResolver r, String name, float value) {
        try {
            return Settings.System.putFloat(r, name, value);
        } catch (SecurityException | IllegalArgumentException e) {
            refused(name, e);
            return false;
        }
    }

    private static Method semGetIntForUser;

    /** Samsung's per-user read; the keyboard only ever asks for the current user. */
    public static int systemSemGetIntForUser(ContentResolver r, String name, int def, int user) {
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
