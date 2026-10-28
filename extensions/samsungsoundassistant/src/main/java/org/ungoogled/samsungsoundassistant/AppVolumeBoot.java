package org.ungoogled.samsungsoundassistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot or an update, brings AppVolumeService back if any app is turned down. */
public final class AppVolumeBoot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        AppVolumeService.update(c);
    }
}
