package org.ungoogled.honorscreenrecorder;

import android.app.Activity;
import android.content.Intent;

/**
 * The launcher's "Start recording" (MagicOS starts recordings from its own SystemUI tile with the recorder's
 * LMT_ScreenRecorder action): a launcher start of the recorder's start activity counts as that action.
 */
public final class Starter {
    private Starter() {
    }

    public static void launch(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())) intent.setAction(Tiles.LMT);
    }
}
