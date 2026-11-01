package org.ungoogled.honorvideoplayer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * "Wireless Projection" in the player menu opens MagicOS's projection device picker (Honor's airsharing /
 * mirrorshare), which only Honor's ROM has: off MagicOS the call threw ActivityNotFoundException and closed the app.
 * Without Honor's picker it opens Android's own Cast screen instead (where the phone has one), else nothing happens.
 */
public final class Projection {
    private Projection() {
    }

    public static void startActivityForResult(Activity activity, Intent intent, int requestCode) {
        try {
            activity.startActivityForResult(intent, requestCode);
            return;
        } catch (ActivityNotFoundException e) {
            Log.i(Media.TAG, "no MagicOS projection picker, opening Cast settings");
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_CAST_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(Media.TAG, "projection: " + e);
        }
    }
}
