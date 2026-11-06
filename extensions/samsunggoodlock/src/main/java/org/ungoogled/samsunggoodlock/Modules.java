package org.ungoogled.samsunggoodlock;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which modules Good Lock lists off One UI, and their store entries. Good Lock lists a module only when the Galaxy
 * Store offers it for the phone, and most modules plug into One UI's own SystemUI, lock screen or settings, which other
 * phones do not have. Off One UI it lists the modules this bundle makes work there, and any module that is installed.
 * Their store entries are made from Good Lock's own module list instead of the Galaxy Store's answer, so nothing
 * offers Samsung's unpatched versions as updates.
 */
public final class Modules {
    private static final String TAG = "UA";

    /** The modules the bundle ports to non-One UI phones. */
    private static final Set<String> PORTED = new HashSet<>(Arrays.asList(
            "com.samsung.android.app.homestar",
            "com.samsung.android.wonderland.wallpaper",
            "com.samsung.android.soundassistant"
    ));

    private static final Pattern PACKAGE = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+");

    private Modules() {
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            out.add(f);
        }
        // R8 names fields a, b, c, ... in declaration order.
        out.sort(Comparator.comparing(Field::getName, Comparator.comparingInt(String::length).thenComparing(s -> s)));
        return out;
    }

    /** A module entry's package name: its first String field that is one. */
    private static String packageOf(Object item) {
        try {
            for (Field f : fields(item.getClass())) {
                if (f.getType() != String.class) continue;
                Object value = f.get(item);
                if (value instanceof String && PACKAGE.matcher((String) value).matches()) return (String) value;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Good Lock: no package in " + item, e);
        }
        return null;
    }

    private static PackageInfo installed(Context context, String packageName) {
        try {
            return context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Good Lock's module list, cut to the modules that run off One UI with this bundle and those installed. */
    public static List<Object> keep(List<?> modules) {
        Context context = App.context();
        if (modules == null || context == null) return modules == null ? null : new ArrayList<>(modules);
        List<Object> out = new ArrayList<>();
        for (Object module : modules) {
            String name = packageOf(module);
            if (name == null || PORTED.contains(name) || installed(context, name) != null) out.add(module);
        }
        Log.i(TAG, "Good Lock: listing " + out.size() + " of " + modules.size() + " modules");
        return out;
    }

    /**
     * Store entries for [modules], made of the Galaxy Store's name and icon where it has the module and the installed
     * version (or none), without the store's version, metadata or staged rollout. [entryType] is the store entry class.
     */
    public static List<Object> store(List<?> modules, List<?> storeEntries, Class<?> entryType) {
        if (modules == null) return storeEntries == null ? null : new ArrayList<>(storeEntries);
        try {
            Context context = App.context();
            Constructor<?> constructor = null;
            for (Constructor<?> c : entryType.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 11 && p[0] == String.class && p[10].isEnum()) constructor = c;
            }
            if (constructor == null || context == null) throw new IllegalStateException("no store entry constructor");
            constructor.setAccessible(true);
            Class<?>[] p = constructor.getParameterTypes();
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object deployed = Enum.valueOf((Class) p[10], "DEPLOYED");
            List<Field> f = fields(entryType);
            List<Object> out = new ArrayList<>();
            for (Object module : modules) {
                String name = packageOf(module);
                if (name == null) continue;
                Object known = null;
                if (storeEntries != null) {
                    for (Object entry : storeEntries) if (name.equals(f.get(2).get(entry))) known = entry;
                }
                PackageInfo info = installed(context, name);
                String productId = known != null ? (String) f.get(0).get(known) : name;
                String productName = known != null ? (String) f.get(1).get(known) : name;
                String icon = known != null ? (String) f.get(3).get(known) : "";
                String version = info != null ? info.versionName : "";
                Integer code = info != null ? info.versionCode : 0;
                out.add(constructor.newInstance(productId, productName, name, icon, version, code, 0L, "", null,
                        Boolean.FALSE, deployed));
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Good Lock: store entries kept as they are", e);
            return storeEntries == null ? null : new ArrayList<>(storeEntries);
        }
    }
}
