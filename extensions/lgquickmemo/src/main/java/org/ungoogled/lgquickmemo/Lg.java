package org.ungoogled.lgquickmemo;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What LG's ROM adds to Android's own classes (WindowManager.LayoutParams.bypassKeyFlags and the like), used where the
 * phone has it and answered as "not there" where it does not: a skipped call, 0 / false / null, or for a call that
 * returns its own object (a builder) that object; Fallbacks gives an app's real answers. The patch sends every such
 * call and field access in the app here, through a small generated method per member in LgShims; the key names the
 * member as dex does: "Lowner;->name(params)return" or "Lowner;->name:type".
 */
public final class Lg {
    private static final String TAG = "UA";
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<String, Object> MEMBERS = new ConcurrentHashMap<>();

    private Lg() {
    }

    private static Class<?> type(String descriptor) throws ClassNotFoundException {
        switch (descriptor) {
            case "V": return void.class;
            case "Z": return boolean.class;
            case "B": return byte.class;
            case "S": return short.class;
            case "C": return char.class;
            case "I": return int.class;
            case "J": return long.class;
            case "F": return float.class;
            case "D": return double.class;
        }
        String name = descriptor.startsWith("[") ? descriptor.replace('/', '.')
                : descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        return Class.forName(name, false, Lg.class.getClassLoader());
    }

    private static List<String> parameters(String list) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < list.length()) {
            int start = i;
            while (list.charAt(i) == '[') i++;
            if (list.charAt(i) == 'L') i = list.indexOf(';', i);
            i++;
            out.add(list.substring(start, i));
        }
        return out;
    }

    /** The member a key names, or MISSING where this phone does not have it. */
    private static Object member(String key) {
        Object found = MEMBERS.get(key);
        if (found != null) return found;
        try {
            int arrow = key.indexOf("->");
            Class<?> owner = type(key.substring(0, arrow));
            String rest = key.substring(arrow + 2);
            int paren = rest.indexOf('(');
            if (paren < 0) {
                Field field = owner.getField(rest.substring(0, rest.indexOf(':')));
                field.setAccessible(true);
                found = field;
            } else {
                List<String> list = parameters(rest.substring(paren + 1, rest.indexOf(')')));
                Class<?>[] types = new Class<?>[list.size()];
                for (int i = 0; i < types.length; i++) types[i] = type(list.get(i));
                Method method = owner.getMethod(rest.substring(0, paren), types);
                method.setAccessible(true);
                found = method;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            found = MISSING;
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "LG member missing: " + key);
        }
        MEMBERS.put(key, found);
        return found;
    }

    /**
     * Answers every LG app needs off LG's ROM, or Fallbacks.NONE: the display's stable density (LG's
     * IWindowManager.getDensityDeviceStable, and Android's hidden density getters a system app may use), and Android's
     * hidden calls a re-signed app may not make, answered with public API.
     */
    private static Object common(Object receiver, String key, Object[] args) {
        if (key.startsWith("Landroid/view/IWindowManager;->getDensityDeviceStable(")
                || key.startsWith("Landroid/view/IWindowManager;->getBaseDisplayDensity(")
                || key.startsWith("Landroid/view/IWindowManager;->getInitialDisplayDensity(")) {
            return android.util.DisplayMetrics.DENSITY_DEVICE_STABLE;
        }
        if (key.startsWith("Landroid/database/DatabaseUtils;->queryIsEmpty(") && args.length == 2) {
            return android.database.DatabaseUtils.queryNumEntries((android.database.sqlite.SQLiteDatabase) args[0], (String) args[1]) == 0;
        }
        return Fallbacks.NONE;
    }

    /** What a missing member answers: nothing, zero, false, null, or for a builder-style call its own object. */
    private static Object absent(Object receiver, String key) {
        String type = key.substring(key.lastIndexOf(key.indexOf('(') < 0 ? ':' : ')') + 1);
        switch (type) {
            case "V": return null;
            case "Z": return Boolean.FALSE;
            case "B": return (byte) 0;
            case "S": return (short) 0;
            case "C": return (char) 0;
            case "I": return 0;
            case "J": return 0L;
            case "F": return 0f;
            case "D": return 0d;
        }
        try {
            if (receiver != null && type(type).isInstance(receiver)) return receiver;
        } catch (ReflectiveOperationException | LinkageError e) {
            // The return type itself is LG's.
        }
        return null;
    }

    private static RuntimeException rethrow(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        return new RuntimeException(cause);
    }

    public static Object call(Object receiver, String key, Object[] args) {
        Object m = member(key);
        if (m == MISSING) {
            Object answer = Fallbacks.call(receiver, key, args);
            if (answer == Fallbacks.NONE) answer = common(receiver, key, args);
            return answer != Fallbacks.NONE ? answer : absent(receiver, key);
        }
        // An instance member on a missing object (an LG-only object the phone could not give): nothing.
        if (receiver == null && !java.lang.reflect.Modifier.isStatic(((Method) m).getModifiers())) return absent(null, key);
        try {
            return ((Method) m).invoke(receiver, args);
        } catch (InvocationTargetException e) {
            throw rethrow(e);
        } catch (IllegalAccessException e) {
            return absent(receiver, key);
        }
    }

    /** A super call cannot be made from here; off LG's ROM there is no super method to call anyway. */
    public static Object callSuper(Object receiver, String key, Object[] args) {
        return absent(receiver, key);
    }

    public static Object get(Object receiver, String key) {
        Object f = member(key);
        if (f == MISSING) return absent(receiver, key);
        try {
            return ((Field) f).get(receiver);
        } catch (IllegalAccessException e) {
            return absent(receiver, key);
        }
    }

    public static void set(Object receiver, String key, Object value) {
        Object f = member(key);
        if (f == MISSING) return;
        try {
            ((Field) f).set(receiver, value);
        } catch (IllegalAccessException e) {
            // Left as it is.
        }
    }
}
