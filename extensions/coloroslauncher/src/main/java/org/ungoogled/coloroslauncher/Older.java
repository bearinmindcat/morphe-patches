package org.ungoogled.coloroslauncher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

/**
 * Public Android calls newer than the phone (OnePlus System Launcher 15.6.24 declares Android 11 but calls Android 12 to
 * 15 API without checking): the older call that does the same. Members asks here only where the phone lacks the call,
 * so on newer Android none of this runs.
 */
final class Older {
    private Older() {
    }

    /*
     * PackageManager's flag objects (Android 13's ApplicationInfoFlags.of(...) and friends): Android 11 has those classes
     * but nothing in them, so none can be made. of() answers null and keeps its flags for the call it is made for
     * (`pm.getApplicationInfo(name, ApplicationInfoFlags.of(GET_META_DATA))`, on the same thread right after).
     */
    private static final ThreadLocal<Long> LAST_FLAGS = new ThreadLocal<>();

    private static int flags(Object o) {
        Long v = LAST_FLAGS.get();
        LAST_FLAGS.remove();
        return v == null ? 0 : (int) (long) v;
    }

    /** Throws a checked exception (PackageManager's NameNotFoundException) where the newer call would have. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    /** A typed array of [type] with the elements of [items] (the typed getters return one). */
    private static Object typed(Object[] items, Object type) {
        if (items == null || !(type instanceof Class)) return items;
        Object out = Array.newInstance((Class<?>) type, items.length);
        for (int i = 0; i < items.length; i++) {
            if (!((Class<?>) type).isInstance(items[i])) return null;
            Array.set(out, i, items[i]);
        }
        return out;
    }

    private static Object ofType(Object value, Object type) {
        return value != null && type instanceof Class && !((Class<?>) type).isInstance(value) ? null : value;
    }

    private static final String PM = "Landroid/content/pm/PackageManager;->";

    static Object call(Object receiver, String key, Object[] args) {
        switch (key) {
            // Activities and widgets: their ActivityInfo (Android 11 keeps it in a field).
            case "Landroid/content/pm/LauncherActivityInfo;->getActivityInfo()Landroid/content/pm/ActivityInfo;":
                return field(receiver, "mActivityInfo");
            case "Landroid/content/pm/LauncherActivityInfo;->getLoadingProgress()F":
                return 1f;
            case "Landroid/appwidget/AppWidgetProviderInfo;->getActivityInfo()Landroid/content/pm/ActivityInfo;":
                return field(receiver, "providerInfo");
            // Typed getters of Bundle, Intent and Parcel (Android 13): the untyped ones.
            case "Landroid/os/Bundle;->getParcelable(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;":
                return ofType(((Bundle) receiver).getParcelable((String) args[0]), args[1]);
            case "Landroid/os/Bundle;->getParcelableArray(Ljava/lang/String;Ljava/lang/Class;)[Ljava/lang/Object;":
                return typed(((Bundle) receiver).getParcelableArray((String) args[0]), args[1]);
            case "Landroid/os/Bundle;->getParcelableArrayList(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/ArrayList;":
                return ((Bundle) receiver).getParcelableArrayList((String) args[0]);
            case "Landroid/os/Bundle;->getSerializable(Ljava/lang/String;Ljava/lang/Class;)Ljava/io/Serializable;":
                return ofType(((Bundle) receiver).getSerializable((String) args[0]), args[1]);
            case "Landroid/os/Bundle;->getSparseParcelableArray(Ljava/lang/String;Ljava/lang/Class;)Landroid/util/SparseArray;":
                return ((Bundle) receiver).getSparseParcelableArray((String) args[0]);
            case "Landroid/content/Intent;->getParcelableExtra(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;":
                return ofType(((Intent) receiver).getParcelableExtra((String) args[0]), args[1]);
            case "Landroid/content/Intent;->getParcelableArrayExtra(Ljava/lang/String;Ljava/lang/Class;)[Ljava/lang/Object;":
                return typed(((Intent) receiver).getParcelableArrayExtra((String) args[0]), args[1]);
            case "Landroid/content/Intent;->getParcelableArrayListExtra(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/ArrayList;":
                return ((Intent) receiver).getParcelableArrayListExtra((String) args[0]);
            case "Landroid/content/Intent;->getSerializableExtra(Ljava/lang/String;Ljava/lang/Class;)Ljava/io/Serializable;":
                return ofType(((Intent) receiver).getSerializableExtra((String) args[0]), args[1]);
            case "Landroid/os/Parcel;->readParcelable(Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Object;":
                return ((Parcel) receiver).readParcelable((ClassLoader) args[0]);
            case "Landroid/os/Parcel;->readParcelableArray(Ljava/lang/ClassLoader;Ljava/lang/Class;)[Ljava/lang/Object;":
                return typed(((Parcel) receiver).readParcelableArray((ClassLoader) args[0]), args[1]);
            case "Landroid/os/Parcel;->readParcelableList(Ljava/util/List;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/util/List;": {
                @SuppressWarnings("unchecked") java.util.List<Parcelable> list = (java.util.List<Parcelable>) args[0];
                return ((Parcel) receiver).readParcelableList(list, (ClassLoader) args[1]);
            }
            case "Landroid/os/Parcel;->readSerializable(Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/Object;":
                return ((Parcel) receiver).readSerializable();
            case "Landroid/os/Parcel;->readArrayList(Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/util/ArrayList;":
                return ((Parcel) receiver).readArrayList((ClassLoader) args[0]);
            case "Landroid/os/Parcel;->readArray(Ljava/lang/ClassLoader;Ljava/lang/Class;)[Ljava/lang/Object;":
                return typed(((Parcel) receiver).readArray((ClassLoader) args[0]), args[1]);
            case "Landroid/os/Parcel;->readHashMap(Ljava/lang/ClassLoader;Ljava/lang/Class;Ljava/lang/Class;)Ljava/util/HashMap;":
                return ((Parcel) receiver).readHashMap((ClassLoader) args[0]);
            case "Landroid/os/Parcel;->readList(Ljava/util/List;Ljava/lang/ClassLoader;Ljava/lang/Class;)V":
                ((Parcel) receiver).readList((java.util.List<?>) args[0], (ClassLoader) args[1]);
                return null;
            case "Landroid/os/Parcel;->readMap(Ljava/util/Map;Ljava/lang/ClassLoader;Ljava/lang/Class;Ljava/lang/Class;)V":
                ((Parcel) receiver).readMap((Map<?, ?>) args[0], (ClassLoader) args[1]);
                return null;
            case "Landroid/os/Parcel;->readSparseArray(Ljava/lang/ClassLoader;Ljava/lang/Class;)Landroid/util/SparseArray;":
                return ((Parcel) receiver).readSparseArray((ClassLoader) args[0]);
            // PackageManager's calls with flag objects (Android 13): the ones with int flags.
            case "Landroid/content/pm/PackageManager$ApplicationInfoFlags;->of(J)Landroid/content/pm/PackageManager$ApplicationInfoFlags;":
            case "Landroid/content/pm/PackageManager$ComponentInfoFlags;->of(J)Landroid/content/pm/PackageManager$ComponentInfoFlags;":
            case "Landroid/content/pm/PackageManager$PackageInfoFlags;->of(J)Landroid/content/pm/PackageManager$PackageInfoFlags;":
            case "Landroid/content/pm/PackageManager$ResolveInfoFlags;->of(J)Landroid/content/pm/PackageManager$ResolveInfoFlags;":
                LAST_FLAGS.set((Long) args[0]);
                return null;
            case PM + "getApplicationInfo(Ljava/lang/String;Landroid/content/pm/PackageManager$ApplicationInfoFlags;)Landroid/content/pm/ApplicationInfo;":
                try {
                    return ((PackageManager) receiver).getApplicationInfo((String) args[0], flags(args[1]));
                } catch (PackageManager.NameNotFoundException e) {
                    throw sneaky(e);
                }
            case PM + "getPackageInfo(Ljava/lang/String;Landroid/content/pm/PackageManager$PackageInfoFlags;)Landroid/content/pm/PackageInfo;":
                try {
                    return ((PackageManager) receiver).getPackageInfo((String) args[0], flags(args[1]));
                } catch (PackageManager.NameNotFoundException e) {
                    throw sneaky(e);
                }
            case PM + "getProviderInfo(Landroid/content/ComponentName;Landroid/content/pm/PackageManager$ComponentInfoFlags;)Landroid/content/pm/ProviderInfo;":
                try {
                    return ((PackageManager) receiver).getProviderInfo((ComponentName) args[0], flags(args[1]));
                } catch (PackageManager.NameNotFoundException e) {
                    throw sneaky(e);
                }
            case PM + "getInstalledPackages(Landroid/content/pm/PackageManager$PackageInfoFlags;)Ljava/util/List;":
                return ((PackageManager) receiver).getInstalledPackages(flags(args[0]));
            case PM + "queryBroadcastReceivers(Landroid/content/Intent;Landroid/content/pm/PackageManager$ResolveInfoFlags;)Ljava/util/List;":
                return ((PackageManager) receiver).queryBroadcastReceivers((Intent) args[0], flags(args[1]));
            case PM + "queryIntentActivities(Landroid/content/Intent;Landroid/content/pm/PackageManager$ResolveInfoFlags;)Ljava/util/List;":
                return ((PackageManager) receiver).queryIntentActivities((Intent) args[0], flags(args[1]));
            case PM + "queryIntentContentProviders(Landroid/content/Intent;Landroid/content/pm/PackageManager$ResolveInfoFlags;)Ljava/util/List;":
                return ((PackageManager) receiver).queryIntentContentProviders((Intent) args[0], flags(args[1]));
            case PM + "resolveContentProvider(Ljava/lang/String;Landroid/content/pm/PackageManager$ComponentInfoFlags;)Landroid/content/pm/ProviderInfo;":
                return ((PackageManager) receiver).resolveContentProvider((String) args[0], flags(args[1]));
            case PM + "resolveService(Landroid/content/Intent;Landroid/content/pm/PackageManager$ResolveInfoFlags;)Landroid/content/pm/ResolveInfo;":
                return ((PackageManager) receiver).resolveService((Intent) args[0], flags(args[1]));
            // Touch: fling limits and velocities (Android 14's per-device calls).
            case "Landroid/view/ViewConfiguration;->getScaledMaximumFlingVelocity(III)I":
                return ((android.view.ViewConfiguration) receiver).getScaledMaximumFlingVelocity();
            case "Landroid/view/ViewConfiguration;->getScaledMinimumFlingVelocity(III)I":
                return ((android.view.ViewConfiguration) receiver).getScaledMinimumFlingVelocity();
            case "Landroid/view/VelocityTracker;->getAxisVelocity(I)F":
                return axis((android.view.VelocityTracker) receiver, (Integer) args[0], -1);
            case "Landroid/view/VelocityTracker;->getAxisVelocity(II)F":
                return axis((android.view.VelocityTracker) receiver, (Integer) args[0], (Integer) args[1]);
            case "Landroid/view/VelocityTracker;->isAxisSupported(I)Z": {
                int a = (Integer) args[0];
                return a == android.view.MotionEvent.AXIS_X || a == android.view.MotionEvent.AXIS_Y;
            }
            case "Landroid/widget/EdgeEffect;->onPullDistance(FF)F":
                ((android.widget.EdgeEffect) receiver).onPull((Float) args[0], (Float) args[1]);
                return args[0];
            case "Landroid/util/TypedValue;->deriveDimension(IFLandroid/util/DisplayMetrics;)F": {
                float one = android.util.TypedValue.applyDimension((Integer) args[0], 1f, (android.util.DisplayMetrics) args[2]);
                return one == 0f ? 0f : (Float) args[1] / one;
            }
            // The wallpaper image (Android 14's getDrawable(which); see Wallpapers).
            case "Landroid/app/WallpaperManager;->getDrawable(I)Landroid/graphics/drawable/Drawable;":
                return Wallpapers.getDrawable((android.app.WallpaperManager) receiver, (Integer) args[0]);
            // Drawing.
            case "Landroid/graphics/Bitmap;->getHardwareBuffer()Landroid/hardware/HardwareBuffer;":
                return hardwareBuffer((android.graphics.Bitmap) receiver);
            case "Landroid/graphics/Rect;->inset(Landroid/graphics/Insets;)V": {
                android.graphics.Rect r = (android.graphics.Rect) receiver;
                android.graphics.Insets i = (android.graphics.Insets) args[0];
                r.left += i.left;
                r.top += i.top;
                r.right -= i.right;
                r.bottom -= i.bottom;
                return null;
            }
            case "Landroid/graphics/Canvas;->drawPatch(Landroid/graphics/NinePatch;Landroid/graphics/Rect;Landroid/graphics/Paint;)V":
                ((android.graphics.NinePatch) args[0]).draw((android.graphics.Canvas) receiver, (android.graphics.Rect) args[1],
                        (android.graphics.Paint) args[2]);
                return null;
            case "Landroid/graphics/Canvas;->drawPatch(Landroid/graphics/NinePatch;Landroid/graphics/RectF;Landroid/graphics/Paint;)V": {
                android.graphics.Rect bounds = new android.graphics.Rect();
                ((android.graphics.RectF) args[1]).round(bounds);
                ((android.graphics.NinePatch) args[0]).draw((android.graphics.Canvas) receiver, bounds, (android.graphics.Paint) args[2]);
                return null;
            }
            // The rest of the system.
            case "Landroid/app/AlarmManager;->canScheduleExactAlarms()Z":
            case "Landroid/os/UserManager;->isUserForeground()Z":
                return true;
            case "Landroid/app/WallpaperManager;->getWallpaperInfo(I)Landroid/app/WallpaperInfo;":
                return ((android.app.WallpaperManager) receiver).getWallpaperInfo();
            case "Landroid/media/session/PlaybackState;->isActive()Z": {
                int s = ((android.media.session.PlaybackState) receiver).getState();
                return s == android.media.session.PlaybackState.STATE_PLAYING || s == android.media.session.PlaybackState.STATE_BUFFERING
                        || s == android.media.session.PlaybackState.STATE_CONNECTING
                        || s == android.media.session.PlaybackState.STATE_FAST_FORWARDING
                        || s == android.media.session.PlaybackState.STATE_REWINDING
                        || s == android.media.session.PlaybackState.STATE_SKIPPING_TO_NEXT
                        || s == android.media.session.PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                        || s == android.media.session.PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
            }
            case "Landroid/location/LocationManager;->hasProvider(Ljava/lang/String;)Z":
                return ((android.location.LocationManager) receiver).getAllProviders().contains((String) args[0]);
            case "Landroid/os/Vibrator;->vibrate(Landroid/os/VibrationEffect;Landroid/os/VibrationAttributes;)V":
                ((android.os.Vibrator) receiver).vibrate((android.os.VibrationEffect) args[0]);
                return null;
            case "Landroid/os/LocaleList;->matchesLanguageAndScript(Ljava/util/Locale;Ljava/util/Locale;)Z": {
                java.util.Locale a = (java.util.Locale) args[0], b = (java.util.Locale) args[1];
                return a.getLanguage().equals(b.getLanguage()) && a.getScript().equals(b.getScript());
            }
            case "Landroid/os/SystemClock;->uptimeNanos()J":
                return android.os.SystemClock.uptimeMillis() * 1_000_000L;
            case "Ljava/util/stream/Stream;->toList()Ljava/util/List;":
                return Collections.unmodifiableList(
                        (java.util.List<?>) ((java.util.stream.Stream<?>) receiver).collect(java.util.stream.Collectors.toList()));
        }
        return Fallbacks.NONE;
    }

    static Object get(Object receiver, String key) {
        switch (key) {
            case "Landroid/graphics/Shader$TileMode;->DECAL:Landroid/graphics/Shader$TileMode;":
                return android.graphics.Shader.TileMode.CLAMP;
        }
        return Fallbacks.NONE;
    }

    static Object construct(String key, Object[] args) {
        switch (key) {
            case "Landroid/widget/EdgeEffect;-><init>(Landroid/content/Context;Landroid/util/AttributeSet;)V":
                return new android.widget.EdgeEffect((android.content.Context) args[0]);
            case "Landroid/graphics/drawable/AdaptiveIconDrawable;-><init>(Landroid/graphics/drawable/Drawable;Landroid/graphics/drawable/Drawable;Landroid/graphics/drawable/Drawable;)V":
                return new android.graphics.drawable.AdaptiveIconDrawable((android.graphics.drawable.Drawable) args[0],
                        (android.graphics.drawable.Drawable) args[1]);
        }
        return Fallbacks.NONE;
    }

    /**
     * A hardware bitmap's buffer (Android 12's Bitmap.getHardwareBuffer; Android 11 keeps it private): the bitmap drawn
     * into a buffer of its own through Android's public HardwareRenderer and ImageReader. The launcher blurs it behind
     * its icon menus.
     */
    private static android.hardware.HardwareBuffer hardwareBuffer(android.graphics.Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        long usage = android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT;
        android.media.ImageReader reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1, usage);
        android.graphics.HardwareRenderer renderer = new android.graphics.HardwareRenderer();
        try {
            android.graphics.RenderNode node = new android.graphics.RenderNode("hardwareBuffer");
            node.setPosition(0, 0, w, h);
            android.graphics.RecordingCanvas canvas = node.beginRecording();
            canvas.drawBitmap(bitmap, 0f, 0f, null);
            node.endRecording();
            renderer.setContentRoot(node);
            renderer.setSurface(reader.getSurface());
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            android.media.Image image = reader.acquireNextImage();
            if (image != null) {
                try {
                    return image.getHardwareBuffer();
                } finally {
                    image.close();
                }
            }
        } catch (RuntimeException e) {
            android.util.Log.w("UA", "no hardware buffer for the bitmap: " + e);
        } finally {
            renderer.destroy();
            reader.close();
        }
        // An empty buffer of the bitmap's size: the menu's blur shows the wallpaper only.
        return android.hardware.HardwareBuffer.create(w, h, android.hardware.HardwareBuffer.RGBA_8888, 1, usage);
    }

    private static float axis(android.view.VelocityTracker t, int axis, int id) {
        if (axis == android.view.MotionEvent.AXIS_X) return id < 0 ? t.getXVelocity() : t.getXVelocity(id);
        if (axis == android.view.MotionEvent.AXIS_Y) return id < 0 ? t.getYVelocity() : t.getYVelocity(id);
        return 0f;
    }

    private static Object field(Object receiver, String name) {
        if (receiver == null) return null;
        for (Class<?> c = receiver.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(receiver);
            } catch (NoSuchFieldException e) {
                // Look in the superclass.
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }
}
