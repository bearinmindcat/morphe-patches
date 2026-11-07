package org.ungoogled.samsunggoodlock;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

/**
 * Sets a Discover wallpaper off One UI (see Wallpapers): the downloaded picture from Good Lock's own file provider goes
 * to WallpaperManager.setStream on the screens picked in Good Lock (FLAG_SYSTEM home, FLAG_LOCK lock, both). Android
 * fits it to the screen. No window of its own; the result (RESULT_OK once set) goes back to Good Lock, which then
 * deletes the download as it does after One UI's wallpaper screen.
 */
public final class WallpaperActivity extends Activity {
    private static final String TAG = "UA";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri picture = getIntent().getData();
        String type = getIntent().getType();
        int which = getIntent().getIntExtra(Wallpapers.WHICH, 0) & (WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
        if (which == 0) which = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
        if (picture == null) {
            done(false, null);
            return;
        }
        if (type != null && type.startsWith("video/")) {
            // Only One UI plays a video as a wallpaper (Samsung's cover screen and lock screen videos).
            done(false, "Video wallpapers need One UI");
            return;
        }
        Context app = getApplicationContext();
        WallpaperManager manager = WallpaperManager.getInstance(app);
        if (!manager.isWallpaperSupported() || !manager.isSetWallpaperAllowed()) {
            done(false, "This phone does not allow changing the wallpaper");
            return;
        }
        int flags = which;
        ContentResolver resolver = app.getContentResolver();
        new Thread(() -> {
            boolean set;
            try (InputStream in = resolver.openInputStream(picture)) {
                set = in != null && manager.setStream(in, null, true, flags) != 0;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Good Lock: wallpaper not set", e);
                set = false;
            }
            Log.i(TAG, "Good Lock: wallpaper " + (set ? "set" : "not set") + ", flags=" + flags);
            boolean ok = set;
            runOnUiThread(() -> done(ok, ok ? "Wallpaper set" : "Couldn't set the wallpaper"));
        }, "GoodLockWallpaper").start();
    }

    private void done(boolean set, String message) {
        if (isDestroyed()) return;
        if (message != null) Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        setResult(set ? RESULT_OK : RESULT_CANCELED);
        finish();
    }
}
