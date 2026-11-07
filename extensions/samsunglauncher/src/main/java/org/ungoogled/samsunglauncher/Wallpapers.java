package org.ungoogled.samsunglauncher;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * The wallpaper picture, which One UI Home reads for the home screen picture of a Home Up backup. Reading it needs
 * READ_WALLPAPER_INTERNAL, which only system apps get: the refused read stopped the backup half way (no picture,
 * never "completed", Home Up said the backup failed). Where it is refused the picture gets the wallpaper's main colour
 * behind the home screen instead, and the backup goes on.
 */
public final class Wallpapers {
    private static final int DARK = 0xFF202124;

    private Wallpapers() {
    }

    public static Drawable getDrawable(WallpaperManager manager) {
        try {
            return manager.getDrawable();
        } catch (SecurityException refused) {
            int color = DARK;
            try {
                WallpaperColors colors = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                if (colors != null) color = colors.getPrimaryColor().toArgb() | 0xFF000000;
            } catch (RuntimeException e) {
                // the dark colour then
            }
            Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(color);
            return new BitmapDrawable(Resources.getSystem(), bitmap);
        }
    }
}
