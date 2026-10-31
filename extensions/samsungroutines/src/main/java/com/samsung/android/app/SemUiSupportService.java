package com.samsung.android.app;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Modes and Routines uses, answering "absent" off One UI (no Samsung code).
 * A service with a window on One UI; elsewhere a plain service without one. */
public class SemUiSupportService extends android.app.Service {
    public SemUiSupportService() {
    }
    public android.view.Window getWindow() {
        return null;
    }
    public void onCreate() {
        super.onCreate();
    }
    public int onStartCommand(android.content.Intent p0, int p1, int p2) {
        return START_NOT_STICKY;
    }

    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        return null;
    }
}
