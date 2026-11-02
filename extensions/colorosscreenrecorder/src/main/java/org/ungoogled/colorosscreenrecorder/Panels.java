package org.ungoogled.colorosscreenrecorder;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/**
 * The Screen recording tile starts a recording by opening the recorder's MainActivity, then closes the notification
 * shade with StatusBarManager's hidden collapsePanels(), which Android refuses to anything but a system app: off ColorOS
 * the shade stayed open over the capture dialog and the recorder's own questions, and without "Display over other
 * apps" Android may refuse a tile's plain startActivity. A tile may open an activity and close the shade in one go
 * (startActivityAndCollapse); the tile does that.
 */
public final class Panels {
    private Panels() {
    }

    /** RecordStarter.start's startActivity: from the tile, open it and close the shade. */
    @SuppressWarnings("deprecation")
    public static void startActivity(Context context, Intent intent) {
        if (context instanceof TileService) {
            TileService tile = (TileService) context;
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    tile.startActivityAndCollapse(PendingIntent.getActivity(tile, 0, intent,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                } else {
                    tile.startActivityAndCollapse(intent);
                }
                return;
            } catch (RuntimeException e) {
                android.util.Log.w("ScreenRecorderPort", "tile could not close the shade", e);
            }
        }
        context.startActivity(intent);
    }
}
