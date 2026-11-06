package org.ungoogled.sonylauncher;

import android.content.Context;

/** The app's own context. (From One UI Home's App.) */
final class App {
    private static Context context;

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
}
