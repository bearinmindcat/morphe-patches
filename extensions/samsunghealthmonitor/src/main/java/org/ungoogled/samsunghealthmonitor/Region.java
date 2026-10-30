package org.ungoogled.samsunghealthmonitor;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.Locale;

/**
 * The country a Samsung phone was sold for (its CSC country) is a One UI system property; elsewhere there is none,
 * and Health Monitor's set-up stops at "not supported". Off One UI the patch answers it with the country of the SIM
 * (or, without one, of the mobile network): what a phone bought in that country would say.
 */
public final class Region {
    private static final String TAG = "UA";

    private Region() {
    }

    /** Health Monitor's own answer when it has one, otherwise the SIM's country ("" without a SIM). */
    public static String csc(String own) {
        if (own != null && !own.isEmpty()) return own;
        String country = "";
        try {
            Context c = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            TelephonyManager t = c == null ? null : c.getSystemService(TelephonyManager.class);
            if (t != null) {
                country = t.getSimCountryIso();
                if (country == null || country.isEmpty()) country = t.getNetworkCountryIso();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No context or no telephony: no country, as with no SIM.
        }
        country = country == null ? "" : country.toUpperCase(Locale.ROOT);
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "CSC country from the SIM: " + country);
        return country;
    }
}
