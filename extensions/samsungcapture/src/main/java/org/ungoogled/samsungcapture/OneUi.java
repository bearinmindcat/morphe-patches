package org.ungoogled.samsungcapture;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What One UI adds to Android's own classes (methods and fields named sem..., SEM_...) and One UI's own framework
 * classes (SemStatusBarManager, SemDvfsManager, ...), used where the phone has them and answered as "not there" where
 * it does not: a skipped call, 0 / false / null, or for a call that returns its own object (a builder) that object.
 * The patch sends every such call and field access in the app here, through a small generated method per member; the
 * key names the member as dex does: "Lowner;->name(params)return" or "Lowner;->name:type".
 *
 * On a Galaxy phone the members are there, but this copy is a regular app signed with another key: what needs
 * Samsung's signature or a privileged permission is refused (SecurityException). Such members are never used
 * (Fallbacks.always: this copy's own way, as everywhere else), and any other refused call answers as if the member
 * were missing, instead of stopping the app.
 */
public final class OneUi {
    private static final String TAG = "UA";
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<String, Object> MEMBERS = new ConcurrentHashMap<>();

    private OneUi() {
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
        return Class.forName(name, false, OneUi.class.getClassLoader());
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
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "One UI member missing: " + key);
        }
        MEMBERS.put(key, found);
        return found;
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
            // The return type itself is One UI's.
        }
        return null;
    }

    private static final java.util.Set<String> REFUSED = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Throws [t] as it is, checked or not, so the app's own catch blocks see what a direct call would have thrown. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    private static Object missing(Object receiver, String key, Object[] args) {
        Object answer = Fallbacks.call(receiver, key, args);
        return answer != Fallbacks.NONE ? answer : absent(receiver, key);
    }

    public static Object call(Object receiver, String key, Object[] args) {
        Object always = Fallbacks.always(receiver, key, args);
        if (always != Fallbacks.NONE) return always;
        Object m = member(key);
        if (m == MISSING) return missing(receiver, key, args);
        try {
            return ((Method) m).invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof SecurityException) {
                // Samsung's own copy may; this one may not.
                if (REFUSED.add(key)) Log.w(Projection.TAG, "One UI refused " + key + ": " + cause.getMessage());
                return missing(receiver, key, args);
            }
            throw OneUi.<RuntimeException>sneaky(cause);
        } catch (IllegalAccessException e) {
            return absent(receiver, key);
        }
    }

    /** A super call cannot be made from here; off One UI there is no super method to call anyway. */
    public static Object callSuper(Object receiver, String key, Object[] args) {
        return absent(receiver, key);
    }

    public static Object get(Object receiver, String key) {
        Object f = member(key);
        if (f == MISSING) {
            Object answer = Fallbacks.get(key);
            return answer != Fallbacks.NONE ? answer : absent(receiver, key);
        }
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
