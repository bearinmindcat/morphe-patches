package org.ungoogled.honorhome;

import android.content.Context;

/** The app's own context and where an installed copy of Honor Home keeps its data. */
public final class App {
    private static Context context;

    private App() {
    }

    /** The application context (ActivityThread's, hidden but open to apps). */
    public static Context context() {
        if (context == null) {
            try {
                context = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return context;
    }

    /**
     * Whether the app keeps its data in device-protected storage, as MagicOS's system copy does
     * (defaultToDeviceProtectedStorage, which Android honours only for system apps).
     */
    public static boolean deviceProtectedStorage(Context c) {
        Context app = c.getApplicationContext();
        return (app != null ? app : c).isDeviceProtectedStorage();
    }
}
