package org.ungoogled.samsungwonderland;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Setting a live wallpaper. Wonderland sets its wallpaper service directly through WallpaperManager's hidden
 * setWallpaperComponent..., which only the system may call: off One UI it opens Android's live wallpaper preview for
 * the same service instead, where "Set wallpaper" applies it (home, lock screen or both, as Android offers).
 */
public final class Wallpapers {
    /** What invoke answers for a call it leaves to Wonderland's own reflection. */
    public static final Object NONE = new Object();

    private Wallpapers() {
    }

    /** Wonderland's reflective call [name] on [target]: answered here, or NONE. */
    public static Object invoke(Object target, String name, Object[] args) {
        if (!(target instanceof WallpaperManager) || name == null) return NONE;
        if (name.startsWith("setWallpaperComponent")) {
            ComponentName component = null;
            if (args != null) for (Object a : args) if (a instanceof ComponentName) component = (ComponentName) a;
            if (component != null) preview(component);
            return null;
        }
        if (name.equals("semGetWallpaperComponent") && args != null && args.length > 0 && args[0] instanceof Integer) {
            try {
                WallpaperInfo info = ((WallpaperManager) target).getWallpaperInfo((Integer) args[0]);
                return info == null ? null : info.getComponent();
            } catch (RuntimeException e) {
                return null;
            }
        }
        return NONE;
    }

    private static void preview(ComponentName component) {
        Context context = App.context();
        if (context == null) return;
        Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                context.startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(context, "Pick Wonderland in the list", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException e2) {
                Log.w("UA", "Wonderland: no live wallpaper picker");
            }
        }
    }
}
