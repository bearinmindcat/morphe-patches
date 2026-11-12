package org.ungoogled.coloroscamera;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * "Open photos in your gallery app". The camera's thumbnail, and the photos in its own preview strip, open the
 * picture or video just taken in OPPO's gallery only: an intent addressed to that one package (OnePlus Photos, OPPO
 * Photos), full of hand-over extras for its own transition (a bitmap binder, thumbnail rectangles, a pre-decode
 * service in the gallery). With that gallery disabled or uninstalled the camera stops at "the gallery is missing"
 * (nothing happens) or at an "Enable Photos?" dialog.
 *
 * The patch calls {@link #open} where the camera is about to make that hand-over. If OPPO's gallery is installed and
 * enabled it returns false and the camera carries on exactly as stock. Otherwise the photo goes to whatever app the
 * user picked for viewing pictures, the way any Android camera hands one over: ACTION_VIEW with the content URI, the
 * type, and a read grant for that one item.
 */
public final class Gallery {
    private static final String TAG = "OnePlusCamera";

    /**
     * OPPO's fallback viewer, used by the camera when Photos was uninstalled through ColorOS's "removable apps". The
     * user removed OPPO's gallery then, so it counts as missing: their own gallery app gets the photo.
     */
    private static final String BASE_GALLERY = "com.oplus.gallery.base";

    private static final int DISABLED_FLAGS =
            PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;

    /** One tap can reach two hand-over points (the thumbnail handler, then the seamless one): open it once. */
    private static final long SAME_TAP_MS = 800;

    private static volatile Uri lastUri;
    private static volatile long lastAt;

    private Gallery() {
    }

    /**
     * The gallery package the camera hands photos to (the camera's own GalleryUtil answer). The patch replaces this
     * body with a call to it; null here means "unknown", treated as missing.
     */
    public static String oppoGallery() {
        return null;
    }

    /**
     * Where the camera is about to hand [uri] to OPPO's gallery. [anchor] is the tapped view, or the camera object
     * that holds the activity. Returns true when the photo went to the user's gallery app instead (the camera then
     * skips its own hand-over), false to carry on as stock.
     */
    public static boolean open(Object anchor, Uri uri, String type) {
        try {
            if (uri == null) return false;
            Context context = context(anchor);
            if (context == null) {
                Log.w(TAG, "gallery: no context to open " + uri + " from, stock hand-over");
                return false;
            }
            if (oppoGalleryEnabled(context)) return false;

            long now = SystemClock.elapsedRealtime();
            if (uri.equals(lastUri) && now - lastAt < SAME_TAP_MS) return true;

            final Activity activity = activity(context);
            final Context from = activity != null ? activity : context.getApplicationContext();
            final Intent view = viewIntent(uri, type, activity == null);

            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (activity != null && keyguard != null && keyguard.isKeyguardLocked()) {
                // The camera on the lock screen (secure camera): nothing leaves it while the phone is locked, as in
                // AOSP's camera. Ask for the unlock the camera uses for its own "unlock to continue" steps, then open
                // the photo once the phone is unlocked; cancelled, the camera just stays.
                lastUri = uri;
                lastAt = now;
                final Context app = context.getApplicationContext();
                keyguard.requestDismissKeyguard(activity, new KeyguardManager.KeyguardDismissCallback() {
                    @Override
                    public void onDismissSucceeded() {
                        boolean opened;
                        try {
                            opened = start(from, view);
                        } catch (RuntimeException e) {
                            // The lock-screen camera closing itself on unlock: open it from the app instead.
                            opened = start(app, viewIntent(view.getData(), view.getType(), true));
                        }
                        if (!opened) Log.w(TAG, "gallery: no app opens " + view.getData());
                    }
                });
                Log.i(TAG, "gallery: unlock first, then open " + uri + " in the gallery app");
                return true;
            }

            if (!start(from, view)) {
                // Nothing on the phone opens it: let the camera say so its own way.
                Log.w(TAG, "gallery: no app opens " + uri + " (" + type + "), stock hand-over");
                return false;
            }
            lastUri = uri;
            lastAt = now;
            Log.i(TAG, "gallery: opened " + uri + " (" + type + ") in the gallery app");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "gallery: hand-over failed, stock hand-over", t);
            return false;
        }
    }

    /** True when the camera's own gallery is installed for this user and enabled: then nothing changes. */
    static boolean oppoGalleryEnabled(Context context) {
        String pkg;
        try {
            pkg = oppoGallery();
        } catch (Throwable t) {
            pkg = null;
        }
        if (pkg == null || pkg.isEmpty() || BASE_GALLERY.equals(pkg)) return false;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, DISABLED_FLAGS);
            return info.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static Intent viewIntent(Uri uri, String type, boolean newTask) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, type == null || type.isEmpty() ? null : type);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Starts [intent]; when no app takes the exact type (a RAW .dng, a camera-specific video type), tries the
     * family ("image/*", "video/*"). False when nothing opens it.
     */
    static boolean start(Context from, Intent intent) {
        try {
            from.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            String type = intent.getType();
            int slash = type == null ? -1 : type.indexOf('/');
            if (slash <= 0 || type.endsWith("/*")) return false;
            Intent family = new Intent(intent);
            family.setDataAndType(intent.getData(), type.substring(0, slash) + "/*");
            try {
                from.startActivity(family);
                return true;
            } catch (ActivityNotFoundException e2) {
                return false;
            }
        } catch (SecurityException e) {
            // A URI the camera can't grant: try without the grant (most gallery apps can read the media store).
            Intent plain = new Intent(intent);
            plain.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                from.startActivity(plain);
                return true;
            } catch (RuntimeException e2) {
                return false;
            }
        }
    }

    private static Context context(Object anchor) {
        if (anchor instanceof View) return ((View) anchor).getContext();
        if (anchor instanceof Context) return (Context) anchor;
        if (anchor == null) return null;
        // A camera object (obfuscated names): the activity is one of its fields, or a field of one of its fields.
        Activity direct = activityField(anchor);
        if (direct != null) return direct;
        for (Class<?> c = anchor.getClass(); isApp(c, anchor); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || !isApp(f.getType(), anchor)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(anchor);
                    if (value == null) continue;
                    Activity a = activityField(value);
                    if (a != null) return a;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Activity activityField(Object holder) {
        if (holder instanceof Activity) return (Activity) holder;
        for (Class<?> c = holder.getClass(); isApp(c, holder); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !Activity.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(holder);
                    if (value instanceof Activity) return (Activity) value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** A class of the camera itself (same class loader as the object), not Android's or Java's. */
    private static boolean isApp(Class<?> c, Object of) {
        return c != null && c != Object.class && c.getClassLoader() == of.getClass().getClassLoader();
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
