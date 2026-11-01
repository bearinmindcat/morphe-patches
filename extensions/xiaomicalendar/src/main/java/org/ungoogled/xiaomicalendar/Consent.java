package org.ungoogled.xiaomicalendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Whether Calendar may talk to Xiaomi's servers (daily pictures, holidays, cards, push). On HyperOS that is the user
 * notice the user accepts through the Security app (or Calendar's own notice dialog); off HyperOS the stand-ins answer
 * "Global build", which counts the notice as accepted, so Calendar went online without asking. Calendar's own flag for
 * the accepted notice (key_internet_notice_agreed, which its notice dialog also sets) now decides its server requests,
 * its push registration and when it offers that notice; the home screen asks once, on first launch. Everything local
 * (events, reminders, the lunar calendar) works either way.
 */
public final class Consent {
    private static final String PREFS = "com.android.calendar_preferences";
    private static final String AGREED = "key_internet_notice_agreed";
    private static final String DECLINED = "key_internet_notice_cancelled";

    private static boolean asking;

    private Consent() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True once the user allowed Xiaomi's servers (here or in Calendar's own notice). */
    public static boolean network() {
        try {
            return prefs(Calendars.context()).getBoolean(AGREED, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Calendar's "offer the user notice" check (UserNoticeUtil): when a feature needs the servers and the user has not
     * allowed them, offer Calendar's own notice -- unless they declined and the feature does not insist. Only on an
     * activity: some callers pass the application context, which Calendar's notice dialog cannot be shown on.
     */
    public static boolean offer(Context context, boolean always) {
        if (network() || !(context instanceof Activity)) return false;
        try {
            return always || !prefs(context).getBoolean(DECLINED, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Asks once (first launch), then runs {@code after} (null: nothing). Allow reloads the screen. */
    public static void ask(Activity activity, Runnable after) {
        SharedPreferences prefs = prefs(activity);
        if (prefs.getBoolean(AGREED, false) || prefs.getBoolean(DECLINED, false) || asking) {
            if (after != null) after.run();
            return;
        }
        boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int theme = night ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        try {
            asking = true;
            new AlertDialog.Builder(activity, theme)
                    .setMessage("Xiaomi Calendar can download daily pictures, holidays and other data from Xiaomi's servers.")
                    .setCancelable(false)
                    .setPositiveButton("Allow", (dialog, which) -> {
                        asking = false;
                        prefs.edit().putBoolean(AGREED, true).putBoolean(DECLINED, false).commit();
                        activity.recreate();
                    })
                    .setNegativeButton("Don't allow", (dialog, which) -> {
                        asking = false;
                        prefs.edit().putBoolean(DECLINED, true).commit();
                        if (after != null) after.run();
                    })
                    .show();
        } catch (RuntimeException e) {
            asking = false;
            if (after != null) after.run();
        }
    }
}
