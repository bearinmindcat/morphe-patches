package org.ungoogled.samsungnotes.privacy;

import android.content.Context;
import android.util.Log;

/**
 * Firebase in Notes is only the transport for Samsung Cloud sync pushes (Samsung's SMP over FCM). It used to start at
 * every launch; now it starts only once sync has used it (FCM turned on) or a Samsung account is signed in, and right
 * before sync sets up its pushes.
 */
public final class GoogleServices {
    private static final String TAG = "UA";

    private GoogleServices() {
    }

    /** Notes' start-up: Firebase only if Samsung sync already uses it. */
    public static void firebaseAtStart(Context context) {
        if (context == null) return;
        boolean used = context.getSharedPreferences("com.google.firebase.messaging", Context.MODE_PRIVATE).contains("auto_init")
            || context.getSharedPreferences("LoginInfo", Context.MODE_PRIVATE).getBoolean("ISLOGIN", false);
        if (used) firebase(context);
    }

    /** Samsung sync setting up its pushes: Firebase (once). */
    public static void firebase(Context context) {
        if (context == null) return;
        try {
            Class.forName("com.google.firebase.FirebaseApp").getMethod("initializeApp", Context.class).invoke(null, context);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Firebase not started: " + e);
        }
    }
}
