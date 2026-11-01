package org.ungoogled.redmagicgamespace;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;

/** What Game Space gets from RedMagic OS that an ordinary app asks Android for. */
public final class GameSpace {
    private static final String TAG = "UA";
    private static final String PREFS = "redmagic_port";
    private static final String ASKED = "usage_access_asked";

    private GameSpace() {
    }

    /**
     * A game's size in the lobby (app + data). RedMagic OS's app reads it through a hidden StorageStatsManager call an
     * ordinary app may not make; the public one needs "Usage access" too. Apps live on internal storage, which every
     * volume the lobby asks about is answered with. Null where Android refuses: the lobby shows its placeholder.
     */
    public static String appSize(Context context, String volumeUuid, String packageName, UserHandle user) {
        try {
            StorageStatsManager stats = context.getSystemService(StorageStatsManager.class);
            StorageStats s = stats.queryStatsForPackage(StorageManager.UUID_DEFAULT, packageName, user);
            return Formatter.formatFileSize(context, s.getAppBytes() + s.getDataBytes());
        } catch (Exception e) {
            return null;
        }
    }

    /** Game Space's own Bitmap.recycle() calls: left to the garbage collector (another view may still draw it). */
    public static void keep(android.graphics.Bitmap bitmap) {
    }

    private static boolean hasUsageAccess(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Game time ("Game Achievements", the lobby) comes from Android's usage statistics, which RedMagic OS hands its own
     * app. Elsewhere Game Space needs "Usage access": asked for once, when Game Space Center first opens without it.
     */
    public static void askUsageAccess(View view) {
        try {
            Context context = view.getContext();
            if (!(context instanceof Activity) || hasUsageAccess(context)) return;
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(ASKED, false)) return;
            prefs.edit().putBoolean(ASKED, true).apply();
            Activity activity = (Activity) context;
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Game time")
                .setMessage("Game Achievements and the lobby count your play time from Android's usage statistics. "
                    + "Allow Game Space usage access to see it.")
                .setPositiveButton("Open settings", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
                    try {
                        activity.startActivity(intent);
                    } catch (RuntimeException e) {
                        activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
        } catch (RuntimeException e) {
            Log.w(TAG, "usage access prompt", e);
        }
    }
}
