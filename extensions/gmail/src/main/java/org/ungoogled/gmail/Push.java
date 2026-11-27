package org.ungoogled.gmail;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;

/**
 * microG Gmail's push (issue #216). Since 7.4.0 MicroG-RE registers microG Gmail for push as Gmail
 * (com.google.android.gm, from the spoofed-package meta-data) and routes Gmail's pushes back to microG Gmail through an
 * alias it records at that registration. Two things on Gmail's side have to agree with that:
 * <ul>
 * <li>the device Gmail's notification client (GNP / Chime) registers with Google names the app the token belongs to,
 * which is Gmail now, not microG Gmail's own package ({@link #targetPackage});</li>
 * <li>a token Gmail got from an older MicroG-RE (or before a reinstall of MicroG-RE) is kept by Firebase and never
 * registered again, so MicroG-RE has no alias for it: Gmail registers again once whenever MicroG-RE changed
 * ({@link #prepare}), as clearing Gmail's data would.</li>
 * </ul>
 */
public final class Push {
    private static final String TAG = "UA";
    private static final String MICROG = "app.revanced.android.gms";
    /** MicroG-RE 7.4.0, the first that registers renamed Google apps under their own name and routes back. */
    private static final long ROUTES_SINCE = 255070319L;
    private static final String GMAIL = "com.google.android.gm";
    /** Firebase's token store (FCM and instance id), and the token Gmail's notification client last registered. */
    private static final String FIREBASE_TOKENS = "com.google.android.gms.appid";
    private static final String GNP_TOKEN = "com.google.android.libraries.notifications.GCM";
    private static final String MARK_FILE = "ungoogled_push";
    private static final String MARK_KEY = "microg";

    private static volatile boolean routes;

    private Push() {}

    /** From Gmail's Application.attachBaseContext, before Firebase reads its token store. */
    public static void prepare(Context base) {
        try {
            PackageInfo microg = base.getPackageManager().getPackageInfo(MICROG, 0);
            long version = microg.getLongVersionCode();
            routes = version >= ROUTES_SINCE;
            String mark = version + ":" + microg.firstInstallTime;
            SharedPreferences marks = base.getSharedPreferences(MARK_FILE, Context.MODE_PRIVATE);
            if (mark.equals(marks.getString(MARK_KEY, null))) return;
            base.getSharedPreferences(FIREBASE_TOKENS, Context.MODE_PRIVATE).edit().clear().commit();
            base.getSharedPreferences(GNP_TOKEN, Context.MODE_PRIVATE).edit().remove("reg_id").commit();
            marks.edit().putString(MARK_KEY, mark).commit();
            Log.i(TAG, "gmail push: registering again with microG-RE " + mark);
        } catch (Throwable t) {
            // No microG-RE (NeedsMicrog says so), or storage still locked: next start.
        }
    }

    /** The package Gmail's push target names: Gmail's own when MicroG-RE registered the token as Gmail. */
    public static String targetPackage(String own) {
        return routes ? GMAIL : own;
    }
}
