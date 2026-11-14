package org.ungoogled.gmail;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.Window;

/**
 * microG Gmail cannot run without microG-RE: Google accounts sign in through it, and Gmail asks Play services (that
 * is, microG-RE) for the id of every account, Exchange/IMAP/POP3 ones too -- without it the first start after adding
 * any account crashed, and every start after that (issue #187). While microG-RE is missing, every Gmail screen opens
 * as this one instead, which says so.
 */
public final class NeedsMicrog extends Activity {
    private static final String PACKAGE = "app.revanced.android.gms";
    private static final String DOWNLOAD = "https://github.com/MorpheApp/MicroG-RE/releases";

    private static Application app;

    /** From the app component factory, as Gmail's Application is made. */
    public static void app(Application application) {
        app = application;
    }

    /** From the app component factory, for every screen it makes: this one in place of Gmail's while microG-RE is missing. */
    public static String activity(String className) {
        if (app == null || className == null || className.startsWith("org.ungoogled.") || installed(app)) return className;
        return NeedsMicrog.class.getName();
    }

    private static boolean installed(Context c) {
        try {
            ApplicationInfo info = c.getPackageManager().getApplicationInfo(PACKAGE, 0);
            return info.enabled;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The theme of the screen this one stands in for is Gmail's; a plain one of Android's here.
        setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        new AlertDialog.Builder(this)
            .setTitle("microG-RE is not installed")
            .setMessage("microG Gmail needs microG-RE (MicroG-RE or ReVanced GmsCore) for every account, Google or "
                + "not. Install it, then open microG Gmail again.")
            .setPositiveButton("Get MicroG-RE", (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable ignored) {
                }
            })
            .setNegativeButton("Close", null)
            .setOnDismissListener(d -> finish())
            .show();
    }
}
