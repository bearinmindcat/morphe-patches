package org.ungoogled.samsungscreenrecorder;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Calls One UI lets the preinstalled system app make and a regular app may not, made so they can't crash it. */
public final class Guards {
    /** MediaStore columns only One UI's media provider has. */
    private static final String[] ONE_UI_COLUMNS = {"recordingtype", "video_codec_info", "audio_codec_info"};

    private Guards() {
    }

    /** Settings.System.putInt: the recorder turns Android's "show taps" off with it, a setting only the system may write. */
    public static boolean putInt(ContentResolver resolver, String name, int value) {
        try {
            return Settings.System.putInt(resolver, name, value);
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "setting " + name + " not written: " + e.getMessage());
            return false;
        }
    }

    /**
     * startActivity in the launcher and settings screens: the overlay permission screen opens on this app, and a One UI
     * screen the phone doesn't have (Settings' "Advanced features", the settings screen's Up) is skipped.
     */
    public static void startActivity(Activity activity, Intent intent) {
        if (Settings.ACTION_MANAGE_OVERLAY_PERMISSION.equals(intent.getAction()) && intent.getData() == null) {
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
        }
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(Projection.TAG, "no screen for " + intent);
        }
    }

    /**
     * The saved recording's MediaStore entry. Off One UI the provider refuses the columns only Samsung's has, so they
     * are left out then; where even that fails the media scanner adds the file.
     */
    public static Uri insert(ContentResolver resolver, Uri uri, ContentValues values) {
        try {
            return resolver.insert(uri, values);
        } catch (RuntimeException first) {
            boolean removed = false;
            for (String column : ONE_UI_COLUMNS) {
                if (values.containsKey(column)) {
                    values.remove(column);
                    removed = true;
                }
            }
            if (removed) {
                try {
                    return resolver.insert(uri, values);
                } catch (RuntimeException e) {
                    Log.w(Projection.TAG, "MediaStore entry refused: " + e.getMessage());
                }
            }
            String path = values.getAsString("_data");
            Context context = Projection.context();
            if (path != null && context != null) {
                MediaScannerConnection.scanFile(context, new String[]{path}, new String[]{"video/mp4"}, null);
            }
            return null;
        }
    }

    /**
     * The cover the recording shows over password fields (on One UI's password keyboard broadcast): a presentation on
     * the recording's display, which One UI may refuse a regular app. Then the recording goes on without it.
     */
    public static void show(Dialog cover) {
        try {
            cover.show();
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "password cover not shown: " + e.getMessage());
        }
    }

    /**
     * The floating control's WindowManager.addView. On One UI it carries one of Samsung's window extension flags; if One
     * UI refuses that flag to a regular app, the control is added without it.
     */
    public static void addView(WindowManager manager, View view, ViewGroup.LayoutParams params) {
        try {
            manager.addView(view, params);
        } catch (SecurityException e) {
            if (!(params instanceof WindowManager.LayoutParams) || !clearExtensionFlags((WindowManager.LayoutParams) params)) throw e;
            Log.w(Projection.TAG, "floating control added without Samsung's window flags: " + e.getMessage());
            manager.addView(view, params);
        }
    }

    private static boolean clearExtensionFlags(WindowManager.LayoutParams params) {
        try {
            Method clear = WindowManager.LayoutParams.class.getMethod("semClearExtensionFlags", int.class);
            clear.invoke(params, -1);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not that method on this phone: the field below.
        }
        for (Field field : WindowManager.LayoutParams.class.getDeclaredFields()) {
            if (field.getType() != int.class || !field.getName().toLowerCase().contains("extensionflags")) continue;
            try {
                field.setAccessible(true);
                field.setInt(params, 0);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }
        return false;
    }
}
