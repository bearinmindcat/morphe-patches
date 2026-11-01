package org.ungoogled.xiaomiscreenrecorder;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

import java.lang.ref.WeakReference;

/**
 * Closing the notification shade after a tap on the Screen Recorder tile. The app does it with StatusBarManager's
 * hidden collapsePanels(), which Android refuses to anything but a system app, so off HyperOS the shade stayed open
 * over the recorder's floating control. A tile may close the shade by opening an activity; it opens an invisible one
 * (ConsentActivity, which closes at once).
 */
public final class Panels {
    private static WeakReference<TileService> tile = new WeakReference<>(null);

    private Panels() {
    }

    /** QuickService.onStartListening: the tile on screen. */
    public static void listening(TileService service) {
        tile = new WeakReference<>(service);
    }

    /** The app's collapse helper: true when the shade was closed here. */
    @SuppressWarnings("deprecation")
    public static boolean collapse() {
        TileService service = tile.get();
        if (service == null) return false;
        Intent intent = new Intent(service, ConsentActivity.class)
                .putExtra(ConsentActivity.EXTRA_CLOSE_SHADE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                service.startActivityAndCollapse(PendingIntent.getActivity(
                        service, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                service.startActivityAndCollapse(intent);
            }
            return true;
        } catch (RuntimeException e) {
            android.util.Log.w("ScreenRecorderPort", "shade not closed", e);
            return false;
        }
    }
}
