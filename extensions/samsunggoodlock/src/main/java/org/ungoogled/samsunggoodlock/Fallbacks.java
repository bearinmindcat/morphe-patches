package org.ungoogled.samsunggoodlock;

import android.content.pm.PackageManager;

/** The One UI members Good Lock needs a real answer for off One UI: a module's icon is Android's app icon. */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/content/pm/PackageManager;->semGetApplicationIconForIconTray(Ljava/lang/String;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getApplicationIcon((String) args[0]);
            }
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (SecurityException | ClassCastException e) {
            return NONE;
        }
        return NONE;
    }

    static Object construct(String key, Object[] args) {
        return NONE;
    }

    static Object get(String key) {
        return NONE;
    }
}
