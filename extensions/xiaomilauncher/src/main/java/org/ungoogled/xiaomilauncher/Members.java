package org.ungoogled.xiaomilauncher;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Android members an installed app may not use or this phone does not have (hidden API of a system app, Android 16
 * additions), used where the phone allows it and answered as "not there" where it does not: a skipped call,
 * 0 / false / null, or for a call that returns its own object (a builder) that object. The patch sends every such
 * call, field access and `new` in the app here, through a small generated method per member in Hidden; the key names
 * the member as dex does: "Lowner;->name(params)return" or "Lowner;->name:type". (A copy of One UI Home's OneUi.)
 */
public final class Members {
    private static final String TAG = "UA";
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<String, Object> MEMBERS = new ConcurrentHashMap<>();

    private Members() {
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
        return Class.forName(name, false, Members.class.getClassLoader());
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
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "member missing: " + key);
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
            Class<?> c = type(type);
            if (receiver != null && c.isInstance(receiver)) return receiver;
            // Empty containers rather than null: callers iterate them without a check.
            if (c.isArray()) return java.lang.reflect.Array.newInstance(c.getComponentType(), 0);
            if (c == java.util.List.class || c == java.util.Collection.class || c == java.util.ArrayList.class
                    || c == Iterable.class) return new java.util.ArrayList<>();
            if (c == java.util.Map.class || c == java.util.HashMap.class) return new java.util.HashMap<>();
            if (c == java.util.Set.class || c == java.util.HashSet.class) return new java.util.HashSet<>();
            if (c == java.util.Optional.class) return java.util.Optional.empty();
            if (c == android.os.Bundle.class) return new android.os.Bundle();
            if (c == android.graphics.Rect.class) return new android.graphics.Rect();
            if (c == android.graphics.RectF.class) return new android.graphics.RectF();
            if (c == android.graphics.Point.class) return new android.graphics.Point();
            // An Android interface (a system binder interface, a callback): an object whose calls answer "absent", so
            // code that only hands it on or prints it does not stop on null.
            if (c.isInterface() && (c.getName().startsWith("android.") || c.getName().startsWith("com.android.internal."))) {
                return absentInterface(c);
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            // The return type itself is missing.
        }
        return null;
    }

    private static final ConcurrentHashMap<Class<?>, Object> INTERFACES = new ConcurrentHashMap<>();

    /** An object of interface [c] whose calls answer "absent" (asBinder: a binder of its own). */
    private static Object absentInterface(Class<?> c) {
        Object found = INTERFACES.get(c);
        if (found != null) return found;
        try {
            final android.os.Binder binder = new android.os.Binder();
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(Members.class.getClassLoader(), new Class<?>[] { c },
                    (self, method, args) -> {
                        switch (method.getName()) {
                            case "toString": return c.getName() + "(absent)";
                            case "hashCode": return System.identityHashCode(self);
                            case "equals": return args != null && args.length == 1 && args[0] == self;
                            case "asBinder": return binder;
                        }
                        Class<?> r = method.getReturnType();
                        if (r == void.class) return null;
                        if (r == boolean.class) return false;
                        if (r == int.class) return 0;
                        if (r == long.class) return 0L;
                        if (r == float.class) return 0f;
                        if (r == double.class) return 0d;
                        if (r == byte.class) return (byte) 0;
                        if (r == short.class) return (short) 0;
                        if (r == char.class) return (char) 0;
                        return null;
                    });
            INTERFACES.put(c, proxy);
            return proxy;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
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
            return answer != Fallbacks.NONE ? answer : absent(receiver, key);
        }
        try {
            return ((Method) m).invoke(receiver, args);
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof SecurityException)) throw rethrow(e);
            // A call only the system may make (a system service's permission check).
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "refused: " + key + ": " + e.getCause().getMessage());
            Object answer = Fallbacks.call(receiver, key, args);
            return answer != Fallbacks.NONE ? answer : absent(receiver, key);
        } catch (IllegalAccessException e) {
            return absent(receiver, key);
        }
    }

    /** A super call cannot be made from here (a hidden super method is refused anyway): Fallbacks' answer, else absent. */
    public static Object callSuper(Object receiver, String key, Object[] args) {
        Object answer = Fallbacks.call(receiver, key, args);
        return answer != Fallbacks.NONE ? answer : absent(receiver, key);
    }

    public static Object get(Object receiver, String key) {
        Object f = member(key);
        if (f == MISSING) {
            Object answer = Fallbacks.get(receiver, key);
            return answer != Fallbacks.NONE ? answer : absent(receiver, key);
        }
        try {
            return ((Field) f).get(receiver);
        } catch (IllegalAccessException e) {
            return absent(receiver, key);
        }
    }

    /**
     * An object of an Android class whose constructor an installed app may not call (or the phone lacks): the port's
     * own answer (Fallbacks.construct), else the constructor by reflection where Android allows it, else null.
     */
    public static Object construct(String key, Object[] args) {
        Object answer = Fallbacks.construct(key, args);
        if (answer != Fallbacks.NONE) return answer;
        try {
            int arrow = key.indexOf("->");
            Class<?> owner = type(key.substring(0, arrow));
            String rest = key.substring(arrow + 2);
            List<String> list = parameters(rest.substring(rest.indexOf('(') + 1, rest.indexOf(')')));
            Class<?>[] types = new Class<?>[list.size()];
            for (int i = 0; i < types.length; i++) types[i] = type(list.get(i));
            java.lang.reflect.Constructor<?> constructor = owner.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Constructor refused: " + key + ": " + e);
            return null;
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
