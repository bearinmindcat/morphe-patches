package org.ungoogled.honorscreenrecorder;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * The Screen recording tile (MagicOS's is in its own SystemUI). A tap starts a recording, or stops the one running, as
 * MagicOS's tile does; it opens the recorder's start activity and closes the shade in one go, since the capture
 * dialog and the recorder's questions would otherwise open behind the shade.
 */
public final class Tiles extends TileService {
    static final String START_ACTIVITY = "com.hihonor.screenrecorder.activities.ServiceStartActivity";
    static final String LMT = "com.hihonor.screenrecorder.intent.action.LMT_ScreenRecorder";

    private static Tiles listening;

    @Override
    public void onStartListening() {
        listening = this;
        refresh();
    }

    @Override
    public void onStopListening() {
        if (listening == this) listening = null;
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Projection.recording() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        Intent intent = new Intent(LMT).setClassName(this, START_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (RuntimeException e) {
            Log.w(Projection.TAG, "tile could not open the recorder", e);
        }
    }

    /** A recording started or ended: the tile shows it. */
    static void update(Context context) {
        Tiles shown = listening;
        if (shown != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(shown::refresh);
            return;
        }
        if (context == null) return;
        try {
            requestListeningState(context, new ComponentName(context, Tiles.class));
        } catch (RuntimeException e) {
            // no tile added
        }
    }
}
