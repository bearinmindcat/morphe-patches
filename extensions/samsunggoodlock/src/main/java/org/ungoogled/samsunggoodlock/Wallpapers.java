package org.ungoogled.samsunggoodlock;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Discover's wallpapers. Good Lock downloads the picture (after the terms were agreed to, like every request), asks
 * "Lock screen / Home screen / Lock and Home screens" itself and hands the file to One UI's "Wallpaper and style"
 * (com.samsung.android.app.dressroom.action.SET_AS_WALLPAPER, "which" = WallpaperManager's FLAG_SYSTEM / FLAG_LOCK).
 * Nothing else answers that action, so off One UI it goes to WallpaperActivity, which sets the picture through
 * Android's own WallpaperManager on the screens already picked.
 */
public final class Wallpapers {
    static final String WHICH = "which";
    private static final String TAG = "UA";

    private Wallpapers() {
    }

    /** The intent Good Lock launches for a result: its own where One UI answers it, else WallpaperActivity. */
    public static Intent route(Intent intent) {
        try {
            Context context = App.context();
            if (context == null || intent == null) return intent;
            if (intent.resolveActivity(context.getPackageManager()) != null) return intent;
            int which = intent.getIntExtra(WHICH, WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            Log.i(TAG, "Good Lock: wallpaper set here, which=" + which + " type=" + intent.getType());
            return new Intent(context, WallpaperActivity.class)
                    .setDataAndType(intent.getData(), intent.getType())
                    .putExtra(WHICH, which)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException e) {
            Log.w(TAG, "Good Lock: wallpaper intent left as is", e);
            return intent;
        }
    }
}
