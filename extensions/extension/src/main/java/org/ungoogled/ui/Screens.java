package org.ungoogled.ui;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Our screens on a root "mount" install (issue #25). Morphe Manager's root installer
 * bind-mounts the patched APK over stock Maps' base.apk, and Android keeps the manifest it
 * parsed from the stock one: our code and resources run, but no Activity or service we add to
 * the manifest exists, so opening Customization threw ActivityNotFoundException.
 *
 * A screen whose Activity is not declared opens inside one stock Maps declares instead -- its
 * open-source licences screen, private to the app and opaque -- and the app's component
 * factory (androidx.core's CoreComponentFactory, which the manifest names and the patch hooks)
 * creates our class in its place. The intent's action names the screen; the licences screen
 * itself, opened by Maps with no such action, is left alone. A normal install declares our
 * Activities and keeps opening them directly.
 */
public final class Screens {
    /** Stock Maps' open-source licences screen. */
    static final String HOST = "com.google.android.libraries.social.licenses.LicenseActivity";
    /** The host intent's action: this, then the screen's class name. */
    private static final String ACTION = "org.ungoogled.ui.SCREEN:";
    /** The screens a host may stand in for. */
    private static final Class<?>[] SCREENS = {
            CustomizationActivity.class, ProxyActivity.class, YouActivity.class, TimelineActivity.class };
    private static final Map<String, Boolean> DECLARED = new ConcurrentHashMap<>();

    private Screens() {}

    /** Opens [screen]: the Activity itself when the manifest declares it, otherwise inside the host. */
    static Intent intent(Context c, Class<? extends Activity> screen) {
        Intent i = new Intent();
        if (declared(c, screen)) return i.setClassName(c.getPackageName(), screen.getName());
        return i.setClassName(c.getPackageName(), HOST).setAction(ACTION + screen.getName());
    }

    /**
     * The component factory, about to create Activity [className] for [intent]: our screen's class
     * when the host is opened for one, else [className] unchanged. Runs for every Activity Maps
     * starts, so it reads only the action -- the extras are not unparcelled this early.
     */
    public static String activityFor(String className, Intent intent) {
        try {
            if (intent == null || !HOST.equals(className)) return className;
            String action = intent.getAction();
            if (action == null || !action.startsWith(ACTION)) return className;
            String screen = action.substring(ACTION.length());
            for (Class<?> k : SCREENS) if (k.getName().equals(screen)) return screen;
        } catch (Throwable t) {
            android.util.Log.w("UA", "screen host", t);
        }
        return className;
    }

    /** Whether the manifest Android uses declares [component]: false on a mount install. */
    static boolean declared(Context c, Class<?> component) {
        String name = component.getName();
        Boolean known = DECLARED.get(name);
        if (known != null) return known;
        boolean found;
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName cn = new ComponentName(c.getPackageName(), name);
            if (Service.class.isAssignableFrom(component)) pm.getServiceInfo(cn, 0);
            else pm.getActivityInfo(cn, 0);
            found = true;
        } catch (PackageManager.NameNotFoundException e) {
            found = false;
        } catch (Throwable t) {
            return true;
        }
        DECLARED.put(name, found);
        return found;
    }

    /**
     * First thing in a screen's onCreate: inside the host it was given the host's theme (AppCompat,
     * Maps' licences style), so it takes the one its own manifest entry names.
     */
    static void prepare(Activity a) {
        ComponentName cn = a.getComponentName();
        if (cn != null && !cn.getClassName().equals(a.getClass().getName())) {
            a.setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
    }
}
