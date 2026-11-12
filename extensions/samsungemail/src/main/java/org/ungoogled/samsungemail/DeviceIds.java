package org.ungoogled.samsungemail;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * The device ID Email gives Exchange ActiveSync servers (Exchange, Outlook.com / Hotmail). Samsung derives it from the
 * phone's IMEI (or the tablet's serial number), which only a preloaded app may read: elsewhere there was no ID and
 * adding an Exchange or Outlook account failed with "Device activation failed. Check your device ID". ActiveSync only
 * needs an ID that is unique to this client, so it is random, made once per install and kept, in Samsung's own format
 * ("SEC1" + 12 hex digits). No hardware ID is read.
 */
public final class DeviceIds {
    private static final String PREFS = "ungoogled_eas";
    private static final String KEY = "device_id";

    private static String id;

    private DeviceIds() {
    }

    /** Replaces Device.getConsistentDeviceId(Context); Email stores the answer in its own preferences as well. */
    public static synchronized String easDeviceId(Context context) {
        if (id != null) return id;
        SharedPreferences prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY, null);
        if (saved == null) {
            byte[] random = new byte[6];
            new SecureRandom().nextBytes(random);
            StringBuilder sb = new StringBuilder("SEC1");
            for (byte b : random) sb.append(String.format(Locale.US, "%02X", b & 0xff));
            saved = sb.toString();
            prefs.edit().putString(KEY, saved).commit();
        }
        id = saved;
        return saved;
    }
}
