package org.ungoogled.samsungphone;

import java.util.ArrayList;
import java.util.List;

/**
 * The permissions Samsung Phone needs before it opens. On One UI the call logs permissions come with the app; elsewhere
 * Android grants them only to the default phone app or to an app whose installer allowed them (adb and root installs
 * do; app installers, Shizuku sessions and the system installer do not), and without them the app closed at start.
 * They are still asked for, but no longer needed to open: without them Recents stays empty (see CallHistoryNote).
 */
public final class Permissions {
    private Permissions() {
    }

    public static String[] required(String[] permissions) {
        if (permissions == null) return null;
        List<String> kept = new ArrayList<>(permissions.length);
        for (String p : permissions) {
            if ("android.permission.READ_CALL_LOG".equals(p) || "android.permission.WRITE_CALL_LOG".equals(p)) continue;
            kept.add(p);
        }
        return kept.size() == permissions.length ? permissions : kept.toArray(new String[0]);
    }
}
