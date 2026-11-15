package org.ungoogled.coloroslauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The accessibility service the user turns on for "Double-tap to lock". Off ColorOS the launcher may not turn the
 * screen off itself (a system permission); through this service it asks Android to lock the screen, the same as the
 * power menu's Lock. It receives no events and reads no screen content.
 */
public final class LockService extends AccessibilityService {
    static volatile LockService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
