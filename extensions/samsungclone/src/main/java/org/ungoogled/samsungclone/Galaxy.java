package org.ungoogled.samsungclone;

import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls into One UI's own framework classes (SemFloatingFeature, SemPersonaManager, SemTelecomManager, ...) from a
 * renamed copy of a Samsung app. On a Galaxy phone those are Samsung's real classes, not the port's stand-ins, and this
 * copy is an ordinary app signed with another key: Samsung's services refuse it what only Samsung's own build may do
 * (SecurityException), hand it no binder (their managers then throw NullPointerException) or fail across the binder
 * (RemoteException). Each such call answers as if the phone did not have it instead of stopping the app. The patch
 * sends every call into those classes here, through a small generated method per member (SamsungClasses); the key
 * names the member as dex does: "Lowner;->name(params)return".
 */
public final class Galaxy {
    private static final String TAG = "UA";
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<String, Object> MEMBERS = new ConcurrentHashMap<>();
    private static final Set<String> REFUSED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private Galaxy() {
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
        return Class.forName(name, false, Galaxy.class.getClassLoader());
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

    /** The method a key names, or MISSING where this phone does not have it (or does not let apps call it). */
    private static Object member(String key) {
        Object found = MEMBERS.get(key);
        if (found != null) return found;
        try {
            int arrow = key.indexOf("->");
            Class<?> owner = type(key.substring(0, arrow));
            String rest = key.substring(arrow + 2);
            int paren = rest.indexOf('(');
            List<String> list = parameters(rest.substring(paren + 1, rest.indexOf(')')));
            Class<?>[] types = new Class<?>[list.size()];
            for (int i = 0; i < types.length; i++) types[i] = type(list.get(i));
            Method method;
            try {
                method = owner.getMethod(rest.substring(0, paren), types);
            } catch (NoSuchMethodException e) {
                method = owner.getDeclaredMethod(rest.substring(0, paren), types);
            }
            method.setAccessible(true);
            found = method;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            found = MISSING;
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "One UI member missing: " + key);
        }
        MEMBERS.put(key, found);
        return found;
    }

    /** What a missing or refused member answers: nothing, zero, false, null, or for a builder-style call its own object. */
    private static Object absent(Object receiver, String key) {
        String type = key.substring(key.lastIndexOf(')') + 1);
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
            // The return type itself is One UI's.
        }
        return null;
    }

    /** Throws [t] as it is, checked or not, so the app's own catch blocks see what a direct call would have thrown. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    /** A refusal of this copy by Samsung's services, rather than an answer the app expects to handle. */
    private static boolean refused(String key, Throwable cause) {
        if (!(cause instanceof SecurityException) && !(cause instanceof NullPointerException)
                && !(cause instanceof android.os.RemoteException)) return false;
        if (REFUSED.add(key)) Log.w(TAG, "One UI refused " + key + ": " + cause);
        return true;
    }

    public static Object call(Object receiver, String key, Object[] args) {
        Object m = member(key);
        if (m == MISSING) return absent(receiver, key);
        // An instance call on an object a refused call did not return answers as missing too.
        if (receiver == null && !java.lang.reflect.Modifier.isStatic(((Method) m).getModifiers())) return absent(null, key);
        try {
            return ((Method) m).invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (refused(key, cause)) return absent(receiver, key);
            throw Galaxy.<RuntimeException>sneaky(cause);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            return absent(receiver, key);
        }
    }

    /** Super calls stay direct (the patch never sends them here); kept for the generated methods' shape. */
    public static Object callSuper(Object receiver, String key, Object[] args) {
        return absent(receiver, key);
    }
}
