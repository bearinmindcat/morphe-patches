package org.ungoogled.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Customization in Maps' own Settings list (issue #164), as the first row of the Settings page. The row is one of the
 * page's own rows (CustomSettingsPreference), made like its first one and drawn with that row's icon; its tap opens
 * the Customization screen.
 */
public final class SettingsRow {
    private SettingsRow() {}

    /** The preference group's add method; set at patch time by Customization screen. */
    static String addMethod() { return ""; }

    private static WeakReference<Object> row = new WeakReference<>(null);

    /** The Settings page is about to add its first row [first] to [screen]: ours goes in first. */
    public static void add(Object screen, Object first) {
        try {
            if (screen == null || first == null) return;
            Class<?> type = first.getClass();
            Constructor<?> make = null;
            for (Constructor<?> c : type.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 5 && p[0] == Context.class && p[1] == String.class && p[2] == String.class && p[3] == String.class) make = c;
            }
            if (make == null) { android.util.Log.w("UA", "settings row: no row constructor"); return; }
            Object icon = fieldOfType(first, make.getParameterTypes()[4]);
            Context context = context(first);
            if (icon == null || context == null) { android.util.Log.w("UA", "settings row: no icon or context"); return; }
            Object pref = make.newInstance(context, "ua_customization", "Customization", "Theme, shapes, ads and more", icon);
            Method add = null;
            for (Class<?> k = screen.getClass(); k != null && add == null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.getName().equals(addMethod()) && m.getParameterTypes().length == 1 && m.getParameterTypes()[0].isAssignableFrom(type)) {
                        add = m;
                        break;
                    }
                }
            }
            if (add == null) { android.util.Log.w("UA", "settings row: no " + addMethod() + "()"); return; }
            add.setAccessible(true);
            add.invoke(screen, pref);
            row = new WeakReference<>(pref);
        } catch (Throwable t) {
            android.util.Log.w("UA", "settings row", t);
        }
    }

    /** A Settings row was tapped: true when it was ours, which opens Customization. */
    public static boolean clicked(Object pref) {
        try {
            if (pref == null || pref != row.get()) return false;
            Context c = context(pref);
            if (c == null) return false;
            Intent i = Screens.intent(c, CustomizationActivity.class);
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("UA", "settings row", t);
            return false;
        }
    }

    private static Object fieldOfType(Object o, Class<?> type) throws IllegalAccessException {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() != type || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                return f.get(o);
            }
        }
        return null;
    }

    private static Context context(Object o) throws IllegalAccessException {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(f.getType()) || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof Context) return (Context) v;
            }
        }
        return null;
    }
}
