package org.ungoogled.xiaomigallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.Log;
import android.widget.Toast;

/**
 * Gallery's private album asks for Xiaomi's privacy password, which lives in a HyperOS system app
 * (com.xiaomi.privacypassword): off HyperOS starting it threw ActivityNotFoundException and Gallery froze/crashed. The
 * album opens with the phone's own screen lock instead (Android's confirm-credential screen, fingerprint included where
 * the phone offers it). Its files are kept in Gallery's app folder (Android/data/com.miui.gallery), which other apps
 * cannot open; see the patch.
 */
public final class PrivateAlbum {
    private static final String TAG = "UngoogledGallery";
    private static final String PREFS = "ungoogled_private_album";
    private static final String NOTICE_SHOWN = "notice_shown";
    private static final String LAUNCHER = "androidx.activity.result.ActivityResultLauncher";

    private PrivateAlbum() {
    }

    /**
     * Replaces XmsWrapper.isPrivacyPasswordEnabled: "a password is set" = the phone has a screen lock. Also has Android
     * create Gallery's app folders (the album's files go below them; Gallery may prefer an SD card).
     */
    public static boolean isPasswordEnabled(Context context) {
        try {
            context.getExternalFilesDirs(null);
        } catch (RuntimeException e) {
            Log.w(TAG, "app folder", e);
        }
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    /** Gallery's own first-use dialogs describe Xiaomi's privacy password: never shown (see unlock). */
    public static boolean isFirstUsePrivacyPassword() {
        return false;
    }

    /**
     * Replaces XmsHelper's "confirm password" and "set password" screens. Starts Android's confirm-credential screen
     * through Gallery's result launcher (OK opens the album, back closes it); without a screen lock, Android's "set
     * screen lock" screen. The first time, says how the album works.
     */
    public static void unlock(Activity activity, Object launcher) {
        isPasswordEnabled(activity);
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        Intent intent = keyguard == null ? null
                : keyguard.createConfirmDeviceCredentialIntent(albumName(activity), null);
        if (intent == null) {
            Toast.makeText(activity, "Set a screen lock to use the private album", Toast.LENGTH_LONG).show();
            intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(NOTICE_SHOWN, false)) {
            launch(launcher, intent);
            return;
        }
        boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int theme = night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        Intent next = intent;
        try {
            new AlertDialog.Builder(activity, theme)
                    .setTitle(albumName(activity))
                    .setMessage("It opens with your screen lock. Its items are kept in Gallery's own folder, which other"
                            + " apps can't open. Uninstalling Gallery deletes them, so move them out first.")
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnDismissListener(dialog -> {
                        prefs.edit().putBoolean(NOTICE_SHOWN, true).apply();
                        launch(launcher, next);
                    })
                    .show();
        } catch (RuntimeException e) {
            launch(launcher, next);
        }
    }

    private static String albumName(Context context) {
        int id = context.getResources().getIdentifier("secret_album_display_name", "string", context.getPackageName());
        try {
            if (id != 0) return context.getString(id);
        } catch (RuntimeException e) {
            // English below.
        }
        return "Private album";
    }

    /** ActivityResultLauncher.launch(Object): Gallery's launcher delivers the screen's result to Gallery's callback. */
    private static void launch(Object launcher, Intent intent) {
        try {
            // The launcher is an anonymous subclass; call the method of the public class it extends.
            Class<?> type = launcher.getClass();
            while (type.getSuperclass() != null && !type.getName().equals(LAUNCHER)) type = type.getSuperclass();
            java.lang.reflect.Method launch = (type.getName().equals(LAUNCHER) ? type : launcher.getClass())
                    .getMethod("launch", Object.class);
            launch.setAccessible(true);
            launch.invoke(launcher, intent);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "private album: cannot start " + intent, e);
        }
    }
}
