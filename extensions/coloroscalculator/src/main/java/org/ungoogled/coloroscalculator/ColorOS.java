package org.ungoogled.coloroscalculator;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What OnePlus Calculator's ColorOS stand-ins answer through (generated from .github/scripts/coloros-standins/runtime):
 * the phone's own system properties for ColorOS's SystemProperties wrappers, the Android object a ColorOS graphics
 * wrapper (OplusCanvas, OplusPath, OplusOutline) was made around, and stand-ins of ColorOS's interfaces whose calls
 * answer "absent". On a ColorOS phone the framework's own classes load first, so none of this runs there.
 */
public final class ColorOS {
    private static Method get;
    private static final Map<Object, Object> WRAPPED = Collections.synchronizedMap(new WeakHashMap<>());

    private ColorOS() {
    }

    /** The phone's own value, or def where it has none (Android's hidden SystemProperties, by reflection). */
    public static String prop(String key, String def) {
        try {
            if (get == null) get = Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class);
            Object value = key == null ? null : get.invoke(null, key, "");
            return value == null || ((String) value).isEmpty() ? def : (String) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return def;
        }
    }

    public static int propInt(String key, int def) {
        try {
            String value = prop(key, null);
            return value == null ? def : Integer.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long propLong(String key, long def) {
        try {
            String value = prop(key, null);
            return value == null ? def : Long.decode(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean propBoolean(String key, boolean def) {
        String value = prop(key, "");
        switch (value) {
            case "1": case "y": case "yes": case "on": case "true": return true;
            case "0": case "n": case "no": case "off": case "false": return false;
            default: return def;
        }
    }

    /** Remembers the Android object a ColorOS wrapper stand-in was constructed around. */
    public static void wrap(Object wrapper, Object wrapped) {
        if (wrapper != null && wrapped != null) WRAPPED.put(wrapper, wrapped);
    }

    /** The Android object [wrapper] was constructed around, or null. */
    public static <T> T wrapped(Object wrapper, Class<T> type) {
        Object value = WRAPPED.get(wrapper);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /**
     * A broadcast receiver the app registers at run time without saying whether other apps may send to it (fine for
     * ColorOS's system apps; an installed app targeting Android 14+ gets a SecurityException): exported, as Android
     * registered it before Android 14 (patches/.../coloros/ColorOsReceivers.kt sends the calls here).
     */
    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter) {
        return context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED);
    }

    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter, String permission,
            android.os.Handler handler) {
        return context.registerReceiver(receiver, filter, permission, handler, android.content.Context.RECEIVER_EXPORTED);
    }

    /** The same with flags: kept when they say exported or not, else exported. */
    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter, int flags) {
        return context.registerReceiver(receiver, filter, exported(flags));
    }

    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter, String permission,
            android.os.Handler handler, int flags) {
        return context.registerReceiver(receiver, filter, permission, handler, exported(flags));
    }

    private static int exported(int flags) {
        int either = android.content.Context.RECEIVER_EXPORTED | android.content.Context.RECEIVER_NOT_EXPORTED;
        return (flags & either) == 0 ? flags | android.content.Context.RECEIVER_EXPORTED : flags;
    }

    /**
     * A stand-in of a ColorOS interface (a system service's binder interface, ...) whose calls answer "absent"
     * (false / 0 / null), with Android's own answers for the few that have one.
     */
    public static <T> T absent(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getInitialDisplayDensity": return android.util.DisplayMetrics.DENSITY_DEVICE_STABLE;
                case "hasNavigationBar": return true;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return args != null && args.length == 1 && proxy == args[0];
                case "toString": return type.getName() + " (absent off ColorOS)";
                default: break;
            }
            Class<?> r = method.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (r == float.class) return 0f;
            if (r == double.class) return 0d;
            if (r == short.class) return (short) 0;
            if (r == byte.class) return (byte) 0;
            if (r == char.class) return (char) 0;
            return null;
        }));
    }
}
