package org.ungoogled.samsunglauncher;

import android.content.Context;
import android.content.pm.PackageManager;

/** The app's own context and what Android lets this copy of One UI Home do. */
final class App {
    private static Context context;
    private static Boolean remoteAnimations;

    private App() {
    }

    /** The application context (ActivityThread's, hidden but open to apps). */
    static Context context() {
        if (context == null) {
            try {
                context = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return context;
    }

    /** Whether app predictions may be asked for (PACKAGE_USAGE_STATS, which an installed app only has if granted). */
    static boolean predictions() {
        Context c = context();
        return c != null && c.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether app launches may use remote animations: only a home app installed as a privileged system app may. */
    static boolean remoteAnimations() {
        if (remoteAnimations == null) {
            Context c = context();
            if (c == null) return false;
            remoteAnimations = c.checkSelfPermission("android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return remoteAnimations;
    }
}
