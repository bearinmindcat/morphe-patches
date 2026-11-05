package org.ungoogled.coloroslauncher;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The launcher also reaches Android's hidden parts by reflection, which Android refuses an installed app. The one it
 * cannot do without, a configuration's window bounds (Configuration.windowConfiguration and WindowConfiguration's
 * getters), is answered from the display here; every other refused lookup fails as before. (From One UI Home's
 * Reflect, plus the rotation and the default display's info.)
 */
public final class Reflect {
    private static final String TAG = "UA";

    /** Handed out for Configuration.windowConfiguration: a static field, so Field.get ignores the configuration. */
    public static final WindowConfiguration windowConfiguration = new WindowConfiguration();

    /** A window configuration's answers for the home screen: full screen on the default display. */
    public static final class WindowConfiguration {
        public Rect getBounds() {
            Context c = App.context();
            if (c == null) return new Rect();
            Display d = c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            Point size = new Point();
            d.getRealSize(size);
            return new Rect(0, 0, size.x, size.y);
        }

        public Rect getAppBounds() {
            return getBounds();
        }

        public Rect getMaxBounds() {
            return getBounds();
        }

        public int getRotation() {
            Context c = App.context();
            if (c == null) return 0;
            return c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRotation();
        }

        public int getDisplayRotation() {
            return getRotation();
        }

        public int getWindowingMode() {
            return 1; // fullscreen
        }

        public boolean isAlwaysOnTop() {
            return false;
        }

        public boolean tasksAreFloating() {
            return false;
        }
    }

    /** The default display, or null. */
    static Display defaultDisplay() {
        Context c = App.context();
        return c == null ? null : c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
    }

    /** The default display's DisplayInfo (Display.mDisplayInfo, open to apps), or null. */
    static Object defaultDisplayInfo() {
        Context c = App.context();
        if (c == null) return null;
        try {
            Display d = c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            Field f = Display.class.getDeclaredField("mDisplayInfo");
            f.setAccessible(true);
            return f.get(d);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean windowConfiguration(Class<?> c, String name) {
        return c == android.content.res.Configuration.class && "windowConfiguration".equals(name);
    }

    public static Field getField(Class<?> c, String name) throws NoSuchFieldException {
        try {
            return c.getField(name);
        } catch (NoSuchFieldException e) {
            if (!windowConfiguration(c, name)) throw e;
            return Reflect.class.getField("windowConfiguration");
        }
    }

    public static Field getDeclaredField(Class<?> c, String name) throws NoSuchFieldException {
        try {
            return c.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            if (!windowConfiguration(c, name)) throw e;
            return Reflect.class.getField("windowConfiguration");
        }
    }

    public static Method getMethod(Class<?> c, String name, Class<?>[] types) throws NoSuchMethodException {
        try {
            return c.getMethod(name, types);
        } catch (NoSuchMethodException e) {
            return windowConfigurationMethod(c, name, types, e);
        }
    }

    public static Method getDeclaredMethod(Class<?> c, String name, Class<?>[] types) throws NoSuchMethodException {
        try {
            return c.getDeclaredMethod(name, types);
        } catch (NoSuchMethodException e) {
            return windowConfigurationMethod(c, name, types, e);
        }
    }

    private static Method windowConfigurationMethod(Class<?> c, String name, Class<?>[] types, NoSuchMethodException e)
            throws NoSuchMethodException {
        if (!"android.app.WindowConfiguration".equals(c.getName()) || (types != null && types.length > 0)) throw e;
        try {
            return WindowConfiguration.class.getMethod(name);
        } catch (NoSuchMethodException none) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "WindowConfiguration." + name + " not answered");
            throw e;
        }
    }
}
