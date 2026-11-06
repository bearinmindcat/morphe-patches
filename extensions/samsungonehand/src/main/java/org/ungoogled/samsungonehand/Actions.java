package org.ungoogled.samsungonehand;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * One Hand Operation +'s actions off One UI. On One UI it presses keys (injectInputEvent), calls One UI services and
 * changes secure settings, all of which only the system may do. Here:
 * - the actions Android lets an accessibility service perform (back, home, recents, notifications, quick settings,
 *   power menu, lock screen, screenshot, split screen, all apps, previous app) go through the accessibility service;
 * - the keys the app still presses for itself (volume, media, its soft keys) become Android's own calls;
 * - toggles an app may not flip open Android's panel or page for them;
 * - what needs One UI (Edge panels, pop-up view, Smart select, One UI's one-handed mode, ...) is left out of the
 *   app's action lists.
 * The app's own panels (quick tools, control panel, volume, timeout, fast actions, app launching) run as they are.
 */
public final class Actions {
    /** Global actions (AccessibilityService.GLOBAL_ACTION_...). */
    private static final int BACK = AccessibilityService.GLOBAL_ACTION_BACK;
    private static final int HOME = AccessibilityService.GLOBAL_ACTION_HOME;
    private static final int RECENTS = AccessibilityService.GLOBAL_ACTION_RECENTS;
    private static final int NOTIFICATIONS = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
    private static final int QUICK_SETTINGS = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
    private static final int POWER_DIALOG = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
    private static final int SPLIT_SCREEN = AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN;
    private static final int LOCK_SCREEN = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN;
    private static final int SCREENSHOT = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT;
    private static final int ALL_APPS = AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS;
    private static final int DISMISS_SHADE = AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE;

    /** One UI's own key for "open the notification panel" (the app's keyboard shortcut for it). */
    private static final int KEYCODE_NOTIFICATION_PANEL = 1003;
    /** One UI's own key for "take a screenshot" (its default keyboard shortcuts). */
    private static final int KEYCODE_SCREENSHOT = 1004;
    private static final int FLAG_LONG_PRESS = KeyEvent.FLAG_LONG_PRESS;

    /** Actions that need One UI, its apps or the system's rights: not offered in the app's lists. */
    private static final Set<String> UNAVAILABLE = new HashSet<>(Arrays.asList(
            "key_menu", "key_forward", "key_search", "kill_app", "launch_memory", "run_searcle", "run_finder",
            "run_popup_view", "min_popup_view", "toggle_bluelight", "toggle_privacydisplay", "smart_select",
            "smartcapture_rect", "smartcapture_oval", "smartcapture_gif", "smartcapture_pin", "screen_recorder",
            "show_edge", "samsung_wallet", "toggle_navibar", "toggle_taskbar", "move_screen_down", "reduce_screen",
            "quick_gametools", "quick_nightmode", "launch_modes", "launch_popup", "manual_routine",
            // The app's task switcher lists recent tasks (an app sees only its own), Move screen and the magnifier
            // show a capture of the screen (READ_FRAME_BUFFER), keyboard shortcuts press key combinations, and the
            // virtual touch pad touches the screen live under its own window; widgets need the system's widget binding
            // and the home screen's shortcuts are listed only to the home app.
            "task_switcher", "screen_free_move", "screen_magnifier", "context_menu", "touch_assist", "widget_popup",
            "launch_shortcut"));

    /**
     * Offered whatever One UI's own checks say (they look for One UI parts the replacement does not need). The
     * hardware ones (flash, NFC, mobile data, hotspot) keep the app's own check.
     */
    private static final Set<String> AVAILABLE = new HashSet<>(Arrays.asList(
            "key_back", "key_home", "key_recent", "key_notipanel", "key_quickpanel", "key_power", "global_action",
            "screen_capture", "run_split_view", "key_application", "key_alttab", "run_assist",
            "key_volumeup", "key_volumedn", "key_volume_mute", "key_media_playpause", "key_media_prevsong",
            "key_media_nextsong", "quick_wifi", "quick_bluetooth", "quick_airplane", "quick_dnd", "quick_soundmode",
            "quick_rotation", "user_rotation", "user_rotation2", "screen_timeout"));

    /** Settings only the system (or an adb grant of WRITE_SECURE_SETTINGS) may change. */
    private static final Set<String> SECURE = new HashSet<>(Arrays.asList("color_inversion"));

    private static boolean shadeOpen;
    private static boolean longPressed;

    private Actions() {
    }

    /** For the app's "is this action supported" check: -1 never offered, 1 offered, 0 the app's own answer. */
    public static int supported(String action) {
        if (action == null) return 0;
        if (UNAVAILABLE.contains(action) || action.startsWith("popup:") || action.startsWith("manual_routine")) return -1;
        if (SECURE.contains(action)) return secure() ? 0 : -1;
        if (AVAILABLE.contains(action)) return 1;
        return 0;
    }

    /**
     * Runs an action the app was asked for (a handle swipe, a quick tool, a fast action): true when done here (the app
     * then only gives its touch feedback), false to let the app run it itself.
     */
    public static boolean run(String action) {
        if (action == null) return false;
        Context c = OneHand.context();
        if (c == null) return false;
        OneHand.debug("action " + action);
        if (supported(action) < 0 && !action.startsWith("popup:")) {
            unavailable();
            return true;
        }
        switch (action) {
            case "key_back": return global(BACK);
            case "key_home": return global(HOME);
            case "key_recent": return global(RECENTS);
            case "key_notipanel": return shade(NOTIFICATIONS);
            case "key_quickpanel": return shade(QUICK_SETTINGS);
            case "global_action": return global(POWER_DIALOG);
            case "key_power": return global(LOCK_SCREEN);
            case "screen_capture": return global(SCREENSHOT);
            case "run_split_view": return global(SPLIT_SCREEN);
            case "key_application": return global(ALL_APPS);
            case "key_alttab": return previousApp();
            case "run_assist": return assistant(c);
            case "quick_wifi": return open(c, new Intent(Settings.Panel.ACTION_WIFI));
            case "quick_mobiledata": return open(c, new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
            case "quick_nfc": return open(c, new Intent(Settings.Panel.ACTION_NFC));
            case "quick_bluetooth": return open(c, new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            case "quick_airplane": return open(c, new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
            case "quick_hotspot": return hotspot(c);
            case "quick_dnd": return doNotDisturb(c);
            case "quick_soundmode": return soundMode(c);
            case "quick_rotation": return autoRotate(c);
            case "user_rotation": return rotate(c, false);
            case "user_rotation2": return rotate(c, true);
            case "screen_timeout": return !canWriteSettings(c);
        }
        if (action.startsWith("popup:")) {
            unavailable();
            return true;
        }
        return false;
    }

    /** The app pressing a key (down, then up): Android's own action for it. Always handled here. */
    public static boolean key(int code, int action, int flags, int meta) {
        Context c = OneHand.context();
        if (c == null) return true;
        boolean down = action == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (down) volume(c, code);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                media(c, code, action);
                return true;
        }
        // Keys with an Android action act once: on release, or at a long press (which then skips the release).
        if (down && (flags & FLAG_LONG_PRESS) != 0) {
            longPressed = true;
            switch (code) {
                case KeyEvent.KEYCODE_HOME: assistant(c); return true;
                case KeyEvent.KEYCODE_POWER: global(POWER_DIALOG); return true;
                case KeyEvent.KEYCODE_APP_SWITCH: global(SPLIT_SCREEN); return true;
            }
            return true;
        }
        if (down) {
            longPressed = false;
            return true;
        }
        if (longPressed) {
            longPressed = false;
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_BACK: global(BACK); break;
            case KeyEvent.KEYCODE_HOME: global(HOME); break;
            case KeyEvent.KEYCODE_APP_SWITCH: global(RECENTS); break;
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP: global(LOCK_SCREEN); break;
            case KEYCODE_SCREENSHOT:
            case KeyEvent.KEYCODE_SYSRQ: global(SCREENSHOT); break;
            case KEYCODE_NOTIFICATION_PANEL:
            case KeyEvent.KEYCODE_NOTIFICATION: shade(NOTIFICATIONS); break;
            case KeyEvent.KEYCODE_ALL_APPS: global(ALL_APPS); break;
            case KeyEvent.KEYCODE_SEARCH: open(c, new Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", "")); break;
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_VOICE_ASSIST: assistant(c); break;
            default: unavailable();
        }
        return true;
    }

    /** An accessibility event: a window was shown for this app. */
    static void windowShown(String pkg) {
        if (pkg != null && !"com.android.systemui".equals(pkg)) shadeOpen = false;
    }

    /** One UI's "is the notification panel expanded", as far as this service saw it open. */
    public static boolean panelExpanded() {
        return shadeOpen;
    }

    public static void collapsePanels() {
        if (ActionService.instance != null) global(DISMISS_SHADE);
        shadeOpen = false;
    }

    public static void expandPanel(boolean quickSettings) {
        global(quickSettings ? QUICK_SETTINGS : NOTIFICATIONS);
        shadeOpen = ActionService.instance != null;
    }

    static boolean global(int action) {
        ActionService service = ActionService.instance;
        if (service == null) {
            needService();
            return true;
        }
        if (action != NOTIFICATIONS && action != QUICK_SETTINGS) shadeOpen = false;
        // Android may refuse one (split screen is the launcher's own on many phones): say so.
        if (!service.performGlobalAction(action)) unavailable();
        return true;
    }

    /** The notification shade or quick settings: open, or closed again when this service opened it. */
    static boolean shade(int which) {
        if (shadeOpen && ActionService.instance != null) {
            global(DISMISS_SHADE);
            shadeOpen = false;
            return true;
        }
        global(which);
        shadeOpen = ActionService.instance != null;
        return true;
    }

    /** Recents twice: Android's launcher switches to the previous app when recents is asked for while it shows. */
    private static boolean previousApp() {
        if (ActionService.instance == null) return global(RECENTS);
        global(RECENTS);
        OneHand.MAIN.postDelayed(() -> global(RECENTS), 350);
        return true;
    }

    private static boolean assistant(Context c) {
        if (open(c, new Intent(Intent.ACTION_ASSIST), false)) return true;
        return open(c, new Intent(Intent.ACTION_VOICE_COMMAND));
    }

    private static boolean hotspot(Context c) {
        Intent tether = new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings");
        if (open(c, tether, false)) return true;
        return open(c, new Intent(Settings.ACTION_WIRELESS_SETTINGS));
    }

    private static boolean doNotDisturb(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.isNotificationPolicyAccessGranted()) {
            OneHand.toast("Allow Do Not Disturb access for One Hand Operation +");
            return open(c, new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        }
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        return true;
    }

    /** Sound, vibrate, mute (mute only with Do Not Disturb access, which Android requires for it). */
    private static boolean soundMode(Context c) {
        AudioManager audio = c.getSystemService(AudioManager.class);
        if (audio == null) return true;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        boolean mayMute = nm != null && nm.isNotificationPolicyAccessGranted();
        int next;
        switch (audio.getRingerMode()) {
            case AudioManager.RINGER_MODE_NORMAL: next = AudioManager.RINGER_MODE_VIBRATE; break;
            case AudioManager.RINGER_MODE_VIBRATE: next = mayMute ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL; break;
            default: next = AudioManager.RINGER_MODE_NORMAL;
        }
        try {
            audio.setRingerMode(next);
        } catch (SecurityException e) {
            audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        }
        return true;
    }

    private static boolean autoRotate(Context c) {
        if (!canWriteSettings(c)) return true;
        boolean on = Settings.System.getInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        Settings.System.putInt(c.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 0 : 1);
        OneHand.toast(on ? "Auto rotate off" : "Auto rotate on");
        return true;
    }

    /** Turns the screen a quarter (left or right) from portrait, or back to portrait; auto rotate goes off. */
    private static boolean rotate(Context c, boolean left) {
        if (!canWriteSettings(c)) return true;
        android.content.ContentResolver cr = c.getContentResolver();
        int current = Settings.System.getInt(cr, Settings.System.USER_ROTATION, 0);
        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0);
        Settings.System.putInt(cr, Settings.System.USER_ROTATION, current == 0 ? (left ? 3 : 1) : 0);
        return true;
    }

    /** "Modify system settings" (rotation, screen timeout): Android's page to allow it when it is not allowed yet. */
    static boolean canWriteSettings(Context c) {
        if (Settings.System.canWrite(c)) return true;
        OneHand.toast("Allow One Hand Operation + to modify system settings");
        open(c, new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", c.getPackageName(), null)));
        return false;
    }

    private static boolean secure() {
        Context c = OneHand.context();
        return c != null && c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private static void volume(Context c, int code) {
        AudioManager audio = c.getSystemService(AudioManager.class);
        if (audio == null) return;
        int direction = code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE
                : code == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
        audio.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
    }

    private static void media(Context c, int code, int action) {
        AudioManager audio = c.getSystemService(AudioManager.class);
        if (audio == null) return;
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, action, code, 0));
    }

    private static boolean open(Context c, Intent intent) {
        return open(c, intent, true);
    }

    private static boolean open(Context c, Intent intent, boolean report) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(intent);
            if (ActionService.instance != null) shadeOpen = false;
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (report) unavailable();
            return false;
        }
    }

    static void needService() {
        OneHand.toast("Turn on One Hand Operation + in Settings > Accessibility");
    }

    static void unavailable() {
        OneHand.toast("Not available on this phone");
    }
}
