package org.ungoogled.coloroslauncher;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

/**
 * "Double-tap to lock" off ColorOS. The launcher turned the screen off with PowerManager.goToSleep, which needs a
 * system permission: elsewhere the double tap did nothing. It locks through the launcher's accessibility service
 * (LockService, Android's GLOBAL_ACTION_LOCK_SCREEN); when that is off, turning the option on explains it and opens
 * Android's accessibility page, and a double tap says what is missing.
 */
public final class Lock {
    private static final String ASK = "To lock the screen with a double tap, turn on System Launcher in Accessibility.";
    private static final String MISSING = "Turn on System Launcher in Accessibility to lock";
    private static final String GREYED = "If it's greyed out: tap it once, then App info ⋮ > Allow restricted settings.";

    private Lock() {
    }

    /** PowerManager.goToSleep(time): lock through the accessibility service. */
    public static void goToSleep(PowerManager power, long time) {
        LockService service = LockService.instance;
        if (service != null && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) return;
        Context c = App.context();
        if (c != null) Toast.makeText(c, MISSING, Toast.LENGTH_LONG).show();
    }

    /** Before the launcher's settings store a change: turning "Double-tap to lock" on asks for the service. */
    public static void preferenceChanged(Object preference, Object value) {
        try {
            if (preference == null || !Boolean.TRUE.equals(value) || LockService.instance != null) return;
            // androidx.preference.Preference, the app's own copy: by name.
            Object key = preference.getClass().getMethod("getKey").invoke(preference);
            if (!"launcher_double_click_lock".equals(key)) return;
            Activity activity = activity((Context) preference.getClass().getMethod("getContext").invoke(preference));
            if (activity != null) ask(activity);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // the switch still works; the double tap says what is missing
        }
    }

    private static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static void ask(Activity a) {
        boolean restricted = restricted(a);
        boolean night = (a.getResources().getConfiguration().uiMode & 0x30) == 0x20;
        AlertDialog.Builder b = new AlertDialog.Builder(a, night
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setMessage(restricted ? ASK + "\n\n" + GREYED : ASK)
                .setPositiveButton("Accessibility", (d, w) -> openAccessibility(a))
                .setNegativeButton("Not now", null);
        if (restricted) b.setNeutralButton("App info", (d, w) -> openAppInfo(a));
        AlertDialog dialog = b.show();
        // The launcher's own theme makes Android's dialog buttons white: the system accent instead.
        int accent;
        if (Build.VERSION.SDK_INT >= 31) {
            accent = a.getColor(night ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
        } else {
            accent = night ? 0xff8ab4f8 : 0xff1a73e8;
        }
        for (int which : new int[] { AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL }) {
            android.widget.Button button = dialog.getButton(which);
            if (button != null) button.setTextColor(accent);
        }
    }

    /** Android's page for this service, else the Accessibility list. */
    private static void openAccessibility(Context c) {
        ComponentName service = new ComponentName(c, LockService.class);
        try {
            c.startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, service.flattenToString()));
        } catch (RuntimeException e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** App info, whose menu has "Allow restricted settings". */
    private static void openAppInfo(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.getPackageName(), null)));
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Whether Android 13+ "restricted settings" may grey out the accessibility switch: it applies to apps the package
     * installer marked as installed from a file or a download (an app may not read the app-op itself).
     */
    private static boolean restricted(Context c) {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) != 0) return false;
            InstallSourceInfo source = c.getPackageManager().getInstallSourceInfo(c.getPackageName());
            int from = source.getPackageSource();
            return from == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || from == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }
}
