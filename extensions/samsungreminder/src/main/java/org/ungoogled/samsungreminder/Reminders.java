package org.ungoogled.samsungreminder;

import android.annotation.SuppressLint;
import android.content.Context;

/** Reminder's application, for the helpers called without a context. */
final class Reminders {
    private Reminders() {
    }

    @SuppressLint("PrivateApi")
    static Context context() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
