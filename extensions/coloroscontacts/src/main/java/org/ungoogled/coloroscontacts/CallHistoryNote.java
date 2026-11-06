package org.ungoogled.coloroscontacts;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.ref.WeakReference;

/**
 * The call history reads Android's call log, which needs the Call logs permission. Android lets an app other than the
 * phone app hold it only when it was installed with adb or root (the installer allows it): installed any other way the
 * permission is never offered and the call history stayed empty without a word. The first time it loads without it, a
 * note says so and gives the one command (adb shell or Shizuku's rish) that reinstalls this copy in place, data kept,
 * so the permission can be allowed. After that the call history asks for it once per start: where Android does not
 * allow it the request is turned down silently, where it does the usual prompt comes up, and once allowed the screen
 * reloads.
 */
public final class CallHistoryNote {
    private static final String PREFS = "ungoogled_contacts";
    private static final String SHOWN = "call_history_note_shown";

    private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private static volatile WeakReference<Activity> shownOn = new WeakReference<>(null);
    private static volatile boolean showing;
    private static volatile boolean requested;
    private static volatile boolean reloaded;
    private static final String[] CALL_LOG = {Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG};

    private CallHistoryNote() {
    }

    /** From Application.onCreate (through Calls.init): which of the app's screens is in front. */
    static void init(Context context) {
        if (!(context instanceof Application)) return;
        ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                resumed = new WeakReference<>(activity);
                // Back from the Call logs prompt with the permission: reload, so the call history reads (and watches) the log.
                if (requested && !reloaded && granted(activity)) {
                    reloaded = true;
                    activity.recreate();
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
                if (resumed.get() == activity) resumed = new WeakReference<>(null);
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (shownOn.get() == activity) showing = false;
            }
        });
    }

    private static boolean granted(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    /** The command that reinstalls this copy in place (its package name is the patch's choice). */
    static String command(Context app) {
        return "pm install -r \"$(pm path " + app.getPackageName() + " | cut -d: -f2)\"";
    }

    /** The call history is loading its list. */
    public static void onCallHistoryLoad() {
        Context app = Calls.app();
        if (app == null || showing || granted(app)) return;
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(SHOWN, false)) {
            new Handler(Looper.getMainLooper()).post(() -> show(app, prefs));
        } else if (!requested) {
            requested = true;
            new Handler(Looper.getMainLooper()).post(() -> {
                Activity activity = resumed.get();
                if (activity == null || activity.isFinishing()) {
                    requested = false;
                    return;
                }
                activity.requestPermissions(CALL_LOG, REQUEST);
            });
        }
    }

    /** Outside the app's own request codes; its screens ignore the answer, the resume above handles it. */
    private static final int REQUEST = 0x5A1;

    private static void show(Context app, SharedPreferences prefs) {
        Activity activity = resumed.get();
        if (activity == null || activity.isFinishing() || showing || prefs.getBoolean(SHOWN, false)) return;
        // Counted as seen only once the user answers it: a screen that closes under it (the permission screen at the
        // first start) brings it back next time.
        Runnable seen = () -> prefs.edit().putBoolean(SHOWN, true).apply();
        try {
            showing = true;
            shownOn = new WeakReference<>(activity);
            // Android's own dialog theme: the app's COUI theme draws a plain AlertDialog's buttons in the dialog's colour.
            boolean night = (activity.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            new AlertDialog.Builder(activity, night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle("Call history")
                    .setMessage("The call history needs the Call logs permission, which Android only allows when the"
                            + " app was installed with adb or root. To allow it for this copy, run this once in adb shell"
                            + " or Shizuku's rish, then reopen the app and allow Call logs:\n\n" + command(app)
                            + "\n\nIf you turned it down, allow Call logs in App info > Permissions.")
                    .setPositiveButton(android.R.string.ok, (d, w) -> seen.run())
                    .setNeutralButton("Copy command", (d, w) -> {
                        seen.run();
                        copy(app);
                    })
                    .setOnCancelListener(d -> seen.run())
                    .setOnDismissListener(d -> showing = false)
                    .show();
        } catch (RuntimeException e) {
            showing = false;
        }
    }

    private static void copy(Context app) {
        try {
            ClipboardManager clipboard = app.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("command", command(app)));
            Toast.makeText(app, "Command copied", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            // Nothing to copy to.
        }
    }
}
