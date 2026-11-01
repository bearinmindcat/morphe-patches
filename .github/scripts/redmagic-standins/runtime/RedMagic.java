package org.ungoogled.APP;

import android.app.ActivityOptions;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Android members APP_NAME was built to call as a RedMagic OS system app -- hidden framework methods and fields, some
 * of which an ordinary app may not use and some of which this phone's Android does not have -- used where the phone
 * lets an app use them (by reflection, which applies Android's hidden-API rules to the app) and answered as "not
 * there" otherwise: a skipped call, 0 / false / null, an empty list, a neutral Android object where callers expect one
 * (plain ActivityOptions), the public Android equivalent where there is one, or for a call that returns its own object
 * (a builder) that object. Off RedMagic OS each refused member would be a NoSuchMethodError or NoSuchFieldError the
 * moment it runs. The patch sends every such call and field access in the app here, through a small generated method
 * per member in RedMagicShims; the key names the member as dex does: "Lowner;->name(params)return" or
 * "Lowner;->name:type".
 */
public final class RedMagic {
    private static final String TAG = "UA";
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<String, Object> MEMBERS = new ConcurrentHashMap<>();

    private RedMagic() {
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
        return Class.forName(name, false, RedMagic.class.getClassLoader());
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
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Member missing: " + key);
        }
        MEMBERS.put(key, found);
        return found;
    }

    /** A ZTE feature switch (com.zte.feature.Feature): the port's answer (Fallbacks.feature), else def. */
    public static String feature(String key, String def) {
        String value = key == null ? null : Fallbacks.feature(key);
        return value != null ? value : def;
    }

    public static boolean featureBoolean(String key, boolean def) {
        String value = feature(key, null);
        return value == null ? def : "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    public static int featureInt(String key, int def) {
        try {
            String value = feature(key, null);
            return value == null ? def : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long featureLong(String key, long def) {
        try {
            String value = feature(key, null);
            return value == null ? def : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int myUserId() {
        return android.os.Process.myUserHandle().hashCode();
    }

    /** The public Android equivalent of hidden members RedMagic's apps call, or Fallbacks.NONE. */
    private static Object common(Object receiver, String key, Object[] args) {
        if (key.endsWith(")Landroid/app/ActivityOptions;")) return ActivityOptions.makeBasic();
        switch (key) {
            case "Landroid/content/Context;->getUserId()I":
            case "Landroid/os/UserHandle;->myUserId()I":
            case "Landroid/os/UserHandle;->getCallingUserId()I":
            case "Landroid/app/ActivityManager;->getCurrentUser()I":
            case "Landroid/os/UserManager;->getUserHandle()I":
                return myUserId();
            case "Landroid/os/UserHandle;->getUserId(I)I":
                return ((Integer) args[0]) / 100000;
            case "Landroid/os/UserHandle;->of(I)Landroid/os/UserHandle;":
                return android.os.UserHandle.getUserHandleForUid(((Integer) args[0]) * 100000);
            case "Landroid/content/Context;->startActivityAsUser(Landroid/content/Intent;Landroid/os/Bundle;Landroid/os/UserHandle;)V":
                ((Context) receiver).startActivity((android.content.Intent) args[0], (android.os.Bundle) args[1]);
                return null;
            case "Landroid/content/pm/PackageManager;->getPackageInfoAsUser(Ljava/lang/String;II)Landroid/content/pm/PackageInfo;":
                try {
                    return ((android.content.pm.PackageManager) receiver).getPackageInfo((String) args[0], (Integer) args[1]);
                } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            case "Landroid/content/pm/PackageManager;->queryIntentServicesAsUser(Landroid/content/Intent;II)Ljava/util/List;":
                return ((android.content.pm.PackageManager) receiver).queryIntentServices((android.content.Intent) args[0], (Integer) args[1]);
            case "Landroid/app/KeyguardManager;->isDeviceLocked(I)Z":
                return ((android.app.KeyguardManager) receiver).isDeviceLocked();
            case "Landroid/view/View;->getLocationOnScreen()[I": {
                int[] out = new int[2];
                ((android.view.View) receiver).getLocationOnScreen(out);
                return out;
            }
            case "Landroid/graphics/Point;->set(Landroid/graphics/Point;)V": {
                android.graphics.Point from = (android.graphics.Point) args[0];
                ((android.graphics.Point) receiver).set(from.x, from.y);
                return null;
            }
            case "Landroid/os/PowerManager;->getMaximumScreenBrightnessSetting()I":
                return 255;
            case "Landroid/os/PowerManager;->getMinimumScreenBrightnessSetting()I":
                return 1;
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
        switch (type) {
            case "Ljava/util/List;":
            case "Ljava/util/Collection;":
                return new ArrayList<>();
            case "Ljava/util/Map;":
                return new java.util.HashMap<>();
            case "Ljava/util/Set;":
                return new java.util.HashSet<>();
        }
        try {
            if (receiver != null && type(type).isInstance(receiver)) return receiver;
        } catch (ReflectiveOperationException | LinkageError e) {
            // The return type itself is missing.
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
        try {
            return ((Method) m).invoke(receiver, args);
        } catch (InvocationTargetException e) {
            throw rethrow(e);
        } catch (IllegalAccessException e) {
            return absent(receiver, key);
        }
    }

    /** A super call cannot be made from here; an app may not call the hidden super method anyway. */
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
