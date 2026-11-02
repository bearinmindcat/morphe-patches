package org.ungoogled.colorosclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Whether Clock may fetch its cloud settings from OPPO's servers (HeyTap cloud config, cloudconf-app-*.heytapmobile.com:
 * today only how many timers may run at once). Clock's export build asks nobody -- on OxygenOS the phone's setup
 * covers it -- so off ColorOS it went online on first launch. Now it does only after the user allowed it here, asked
 * once on the home screen; everything else in Clock works either way (the timer limit stays at its built-in 5).
 */
public final class Consent {
    private static final String PREFS = "org.ungoogled.coloros_consent";
    private static final String ALLOWED = "oppo_servers_allowed";
    private static final String ASKED = "oppo_servers_asked";

    private static boolean asking;

    private Consent() {
    }

    private static Context application() {
        try {
            return (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Clock's canAccessCloudConfig(): true only once the user allowed OPPO's servers. */
    public static boolean network() {
        try {
            Context context = application();
            return context != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ALLOWED, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Asks once (the home screen's onResume). */
    public static void ask(Activity activity) {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (asking || prefs.getBoolean(ASKED, false) || activity.isFinishing()) return;
            boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int theme = night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
            asking = true;
            AlertDialog dialog = new AlertDialog.Builder(activity, theme)
                    .setMessage("OnePlus Clock can download settings, such as how many timers can run at once, from OPPO's servers.")
                    .setCancelable(false)
                    .setPositiveButton("Allow", (d, which) -> {
                        asking = false;
                        prefs.edit().putBoolean(ASKED, true).putBoolean(ALLOWED, true).apply();
                    })
                    .setNegativeButton("Don't allow", (d, which) -> {
                        asking = false;
                        prefs.edit().putBoolean(ASKED, true).putBoolean(ALLOWED, false).apply();
                    })
                    .show();
            // Clock's COUI theme shows through the dialog theme and paints the buttons white: use the system accent.
            int accent = android.os.Build.VERSION.SDK_INT >= 31
                    ? activity.getColor(night ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600)
                    : (night ? 0xFF8AB4F8 : 0xFF1A73E8);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent);
        } catch (RuntimeException e) {
            asking = false;
        }
    }
}
