package org.ungoogled.samsungonehand;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The accessibility service the user turns on for One Hand Operation +. Off One UI the app may not press keys, touch
 * the screen or add system windows; through this service it performs Android's own actions (back, home, recents,
 * notifications, ...), plays its taps and swipes as gestures and draws its handles as accessibility overlays.
 *
 * It reads no window content (its configuration does not ask for it): of the events Android sends it, only the package
 * and class name of a newly shown window are used, so the app knows which app is in front (its per-app settings) and
 * when the notification shade was left. Nothing is logged or sent anywhere.
 */
public final class ActionService extends AccessibilityService {
    static volatile ActionService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        OneHand.context(this);
        // The app's handles were refused (or are gone) without this service: its gesture service rebuilds them.
        OneHand.MAIN.post(() -> OneHand.restartGestures(this));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        Actions.windowShown(pkg == null ? null : pkg.toString());
        Tasks.windowShown(this, pkg, event.getClassName());
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        Windows.forget();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        Windows.forget();
        super.onDestroy();
    }
}
