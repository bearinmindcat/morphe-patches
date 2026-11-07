package org.ungoogled.lggallery;

import android.content.Context;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** LG Gallery's answers off LG's ROM that are not LG classes or members (see the patch). */
public final class Compat {
    private static final String TAG = "UA";
    /** Android's StorageVolume.STORAGE_ID_PRIMARY (hidden). */
    public static final int STORAGE_ID_PRIMARY = 0x00010001;

    private Compat() {
    }

    /** Android's VolumeInfo.buildStableMtpStorageId (hidden): the MTP storage id of a removable volume. */
    static int stableStorageId(String uuid) {
        if (uuid == null || uuid.isEmpty()) return 0;
        int hash = 0;
        for (int i = 0; i < uuid.length(); i++) hash = 31 * hash + uuid.charAt(i);
        hash = (hash ^ (hash << 16)) & 0xffff0000;
        if (hash == 0x00000000) hash = 0x00020000;
        if (hash == 0x00010000) hash = 0x00020000;
        if (hash == 0xffff0000) hash = 0xfffe0000;
        return hash | 0x0001;
    }

    /**
     * Gallery's list of mounted storage (internal, SD card, USB drive), which it built through Android's hidden storage
     * classes (refused off LG's ROM: no storage at all, so no Trash and no SD card albums): the same list from the
     * public StorageVolume API, as Gallery's own StorageInfo objects.
     */
    public static List<Object> storageInfo(Context context) {
        ArrayList<Object> list = new ArrayList<>();
        try {
            Class<?> infoClass = Class.forName("com.lge.gallery.sys.StorageHelper$StorageInfo");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Enum> typeClass = (Class<Enum>) Class.forName("com.lge.gallery.sys.StorageHelper$StorageType");
            Constructor<?> constructor = infoClass.getDeclaredConstructor(typeClass, int.class, String.class, boolean.class, long.class, String.class);
            constructor.setAccessible(true);
            StorageManager manager = context.getApplicationContext().getSystemService(StorageManager.class);
            for (StorageVolume volume : manager.getStorageVolumes()) {
                String state = volume.getState();
                if (!"mounted".equalsIgnoreCase(state) && !"mounted_ro".equalsIgnoreCase(state)) continue;
                File directory = volume.getDirectory();
                if (directory == null) continue;
                String path = directory.getPath();
                if (volume.isPrimary()) {
                    list.add(constructor.newInstance(Enum.valueOf(typeClass, "PRIMARY"), STORAGE_ID_PRIMARY, "external_primary",
                            volume.isRemovable(), 0L, path));
                    continue;
                }
                String uuid = volume.getUuid();
                if (uuid == null) continue;
                String description = volume.getDescription(context);
                boolean usb = description != null && description.toUpperCase(Locale.ROOT).contains("USB");
                list.add(constructor.newInstance(Enum.valueOf(typeClass, usb ? "USB" : "SECONDARY"), stableStorageId(uuid),
                        uuid.toLowerCase(Locale.ROOT), true, 0L, path));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "LG Gallery: cannot list storage", e);
        }
        return list;
    }

    /**
     * Whether the phone shows a navigation bar (buttons or a gesture handle). Gallery asked LG's ROM; every phone that
     * runs Android 11 has one, unless an emulator says otherwise (qemu.hw.mainkeys).
     */
    public static boolean hasNavigationBar() {
        try {
            String mainKeys = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class)
                    .invoke(null, "qemu.hw.mainkeys");
            return !"1".equals(mainKeys);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    private static android.graphics.Insets navigationInsets(android.app.Activity activity) {
        android.view.WindowInsets insets = activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets();
        return insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars());
    }

    /**
     * The navigation bar's height at the bottom of [activity]'s window (0 in multi-window, as LG's own answer), from
     * the window's insets: LG's answer came from its ROM's resources and settings, which other phones lack (the bottom
     * tabs and toolbars sat under the navigation bar). -1 lets Gallery answer itself.
     */
    public static int navigationHeight(android.app.Activity activity, boolean multiWindow) {
        if (multiWindow) return 0;
        try {
            return navigationInsets(activity).bottom;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** The same for a navigation bar at the side of the window (landscape with buttons). */
    public static int navigationWidth(android.app.Activity activity, boolean multiWindow) {
        if (multiWindow) return 0;
        try {
            android.graphics.Insets insets = navigationInsets(activity);
            return Math.max(insets.left, insets.right);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Whether the phone uses gesture navigation (Android's navigation mode 2), which Gallery read through a hidden
     * field of Android's internal R class (refused: it always assumed buttons).
     */
    public static boolean isGestureNavigation(Context context) {
        android.content.res.Resources resources = context == null ? android.content.res.Resources.getSystem() : context.getResources();
        int id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android");
        try {
            return id != 0 && resources.getInteger(id) == 2;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The "activity" system service as LG's ActivityManagerEx, which it is on LG's ROM only: elsewhere the stand-in,
     * which answers "no second screen".
     */
    public static android.app.ActivityManagerEx activityManagerEx(Object service) {
        return service instanceof android.app.ActivityManagerEx ? (android.app.ActivityManagerEx) service : new android.app.ActivityManagerEx();
    }

    /**
     * The action mode's close button layout, whose children Gallery hides on its pick screens (they have their own OK
     * button): on LG's ROM a LinearLayout around the button; elsewhere the button itself, which Android reuses for every
     * later selection, so it stays (null: Gallery skips the step).
     */
    public static android.widget.LinearLayout actionModeCloseLayout(android.view.View view) {
        return view instanceof android.widget.LinearLayout ? (android.widget.LinearLayout) view : null;
    }
}
