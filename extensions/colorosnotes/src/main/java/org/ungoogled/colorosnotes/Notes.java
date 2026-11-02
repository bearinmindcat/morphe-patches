package org.ungoogled.colorosnotes;

import android.app.Application;
import android.content.pm.PackageManager;

/** OnePlus Notes's answers where it asks ColorOS's phones something only they can answer. */
public final class Notes {
    private Notes() {
    }

    /**
     * Notes asks for Android's "Turn screen on" special access (to light the screen for a reminder) unless ColorOS's
     * power manager (com.oplus.ipemanager) says it needn't; without that app it always asked, and on Android 15 the
     * Settings page it opens for it can be missing or crash. Reminders post their notification either way, as every
     * other app's do: only ask where ColorOS's power manager is installed.
     */
    public static boolean hasIpeManager() {
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (app == null) return false;
            app.getPackageManager().getApplicationInfo("com.oplus.ipemanager", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
