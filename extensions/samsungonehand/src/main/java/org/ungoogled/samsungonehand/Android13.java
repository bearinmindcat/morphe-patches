package org.ungoogled.samsungonehand;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.app.job.JobScheduler;
import android.content.res.Resources;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Android 14 and newer methods the app calls without checking the version (it was built for a newer Android, so
 * its build dropped the checks). The patch sends each such call through a generated method in this class: from the
 * method's own Android version on it makes the real call; below, it lands here, where {@link #older} answers like the
 * newer method would where Android 13 has a way to, and "not there" elsewhere (nothing, 0, false, an empty list,
 * null, or a builder's own object). Keys name the member as dex does ("Lowner;->name(params)return"; owner = the class at the call site).
 */
public final class Android13 {
    private static final String TAG = "UA";

    private Android13() {
    }

    private static float density() {
        return Resources.getSystem().getDisplayMetrics().density;
    }

    /** The member's name and signature without its owner: "name(params)return". */
    private static String member(String key) {
        return key.substring(key.indexOf("->") + 2);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object older(Object receiver, String key, Object[] args) {
        try {
            switch (member(key)) {
                // Samsung's app bar measures the window in dp (One UI's AppBarLayout, every app).
                case "deriveDimension(IFLandroid/util/DisplayMetrics;)F":
                    return deriveDimension((Integer) args[0], (Float) args[1], (DisplayMetrics) args[2]);
                case "convertDimensionToPixels(IFLandroid/util/DisplayMetrics;)F":
                    return TypedValue.applyDimension((Integer) args[0], (Float) args[1], (DisplayMetrics) args[2]);
                // WorkManager: job namespaces came with Android 14; below there is only the default one.
                case "forNamespace(Ljava/lang/String;)Landroid/app/job/JobScheduler;":
                    return receiver;
                case "getNamespace()Ljava/lang/String;":
                    return null;
                case "cancelInAllNamespaces()V":
                    ((JobScheduler) receiver).cancelAll();
                    return null;
                case "getPendingJobsInAllNamespaces()Ljava/util/Map;":
                    return Collections.singletonMap(null, ((JobScheduler) receiver).getAllPendingJobs());
                case "isDeviceVoiceCapable()Z":
                    return ((TelephonyManager) receiver).isVoiceCapable();
                case "canUseFullScreenIntent()Z":
                    return true;
                case "overrideActivityTransition(III)V":
                case "overrideActivityTransition(IIII)V":
                case "clearOverrideActivityTransition(I)V":
                    return null;
                case "send(Landroid/os/Bundle;)V":
                    if (!(receiver instanceof PendingIntent)) break;
                    ((PendingIntent) receiver).send(null, 0, null, null, null, null, (android.os.Bundle) args[0]);
                    return null;
                case "getDensity()F":
                    if (!key.startsWith("Landroid/view/WindowMetrics;")) break;
                    return density();
                case "getFrame()Landroid/util/Size;": {
                    if (!key.startsWith("Landroid/view/WindowInsets;")) break;
                    DisplayMetrics m = Resources.getSystem().getDisplayMetrics();
                    return new Size(m.widthPixels, m.heightPixels);
                }
                case "getAxisVelocity(I)F":
                    return axisVelocity((VelocityTracker) receiver, (Integer) args[0], -1);
                case "getAxisVelocity(II)F":
                    return axisVelocity((VelocityTracker) receiver, (Integer) args[0], (Integer) args[1]);
                case "isAxisSupported(I)Z":
                    return (Integer) args[0] == 0 || (Integer) args[0] == 1;
                case "getScaledMaximumFlingVelocity(III)I":
                    return ((ViewConfiguration) receiver).getScaledMaximumFlingVelocity();
                case "getScaledMinimumFlingVelocity(III)I":
                    return ((ViewConfiguration) receiver).getScaledMinimumFlingVelocity();
                case "setLineHeight(IF)V": {
                    TextView view = (TextView) receiver;
                    view.setLineHeight(Math.round(TypedValue.applyDimension((Integer) args[0], (Float) args[1],
                            view.getResources().getDisplayMetrics())));
                    return null;
                }
                case "getDrawable(I)Landroid/graphics/drawable/Drawable;":
                    if (receiver instanceof WallpaperManager) return ((WallpaperManager) receiver).getDrawable();
                    break;
                // Java 21's sequenced collections and Java 16's Stream.toList (Android 15 / 14).
                case "toList()Ljava/util/List;":
                    return Collections.unmodifiableList((List<Object>) ((Stream<Object>) receiver).collect(Collectors.toList()));
                case "getFirst()Ljava/lang/Object;":
                    return first((Collection) receiver, false);
                case "getLast()Ljava/lang/Object;":
                    return last((Collection) receiver, false);
                case "removeFirst()Ljava/lang/Object;":
                    return first((Collection) receiver, true);
                case "removeLast()Ljava/lang/Object;":
                    return last((Collection) receiver, true);
                case "addFirst(Ljava/lang/Object;)V":
                    ((List) receiver).add(0, args[0]);
                    return null;
                case "addLast(Ljava/lang/Object;)V":
                    ((Collection) receiver).add(args[0]);
                    return null;
                case "reversed()Ljava/util/List;": {
                    List<Object> copy = new ArrayList<>((List<Object>) receiver);
                    Collections.reverse(copy);
                    return copy;
                }
                case "isEmpty()Z":
                    if (receiver instanceof CharSequence) return ((CharSequence) receiver).length() == 0;
                    break;
                case "clamp(FFF)F":
                    return Math.min(Math.max((Float) args[0], (Float) args[1]), (Float) args[2]);
                case "clamp(DDD)D":
                    return Math.min(Math.max((Double) args[0], (Double) args[1]), (Double) args[2]);
                case "clamp(JJJ)J":
                    return Math.min(Math.max((Long) args[0], (Long) args[1]), (Long) args[2]);
                case "clamp(JII)I":
                    return (int) Math.min(Math.max((Long) args[0], (Integer) args[1]), (Integer) args[2]);
                case "of(Ljava/lang/String;)Ljava/util/Locale;":
                    return new Locale((String) args[0]);
                case "of(Ljava/lang/String;Ljava/lang/String;)Ljava/util/Locale;":
                    return new Locale((String) args[0], (String) args[1]);
                case "of(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/util/Locale;":
                    return new Locale((String) args[0], (String) args[1], (String) args[2]);
                case "skipNBytes(J)V":
                    skipNBytes((InputStream) receiver, (Long) args[0]);
                    return null;
                default:
                    break;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (PendingIntent.CanceledException e) {
            throw new IllegalStateException(e);
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "Android 14+ member answered as absent: " + key);
        return absent(receiver, key);
    }

    /**
     * RenderEffect.createBlurEffect: Android 13 fails on a blur of radius 0 ("nativePtr is null"), which later versions
     * draw as no blur; Samsung's animations start their blur at 0. Below Android 14 that becomes a blur too small to see.
     */
    public static RenderEffect createBlurEffect(float x, float y, Shader.TileMode mode) {
        if (Build.VERSION.SDK_INT < 34 && x <= 0f && y <= 0f) x = y = 0.01f;
        return RenderEffect.createBlurEffect(x, y, mode);
    }

    public static RenderEffect createBlurEffect(float x, float y, RenderEffect input, Shader.TileMode mode) {
        if (Build.VERSION.SDK_INT < 34 && x <= 0f && y <= 0f) x = y = 0.01f;
        return RenderEffect.createBlurEffect(x, y, input, mode);
    }

    /** Fields Android 13 doesn't have. */
    public static Object olderField(Object receiver, String key) {
        if (member(key).equals("advertisedMem:J") && receiver instanceof ActivityManager.MemoryInfo) {
            return ((ActivityManager.MemoryInfo) receiver).totalMem;
        }
        return absent(receiver, key);
    }

    /** The value in [unit] for [pixels] pixels: TypedValue.applyDimension backwards. */
    private static float deriveDimension(int unit, float pixels, DisplayMetrics metrics) {
        float one = TypedValue.applyDimension(unit, 1f, metrics);
        return one == 0f ? 0f : pixels / one;
    }

    private static float axisVelocity(VelocityTracker tracker, int axis, int pointer) {
        if (axis == 0) return pointer < 0 ? tracker.getXVelocity() : tracker.getXVelocity(pointer);
        if (axis == 1) return pointer < 0 ? tracker.getYVelocity() : tracker.getYVelocity(pointer);
        return 0f;
    }

    @SuppressWarnings("rawtypes")
    private static Object first(Collection c, boolean remove) {
        if (c.isEmpty()) throw new java.util.NoSuchElementException();
        if (c instanceof List) return remove ? ((List) c).remove(0) : ((List) c).get(0);
        java.util.Iterator it = c.iterator();
        Object value = it.next();
        if (remove) it.remove();
        return value;
    }

    @SuppressWarnings("rawtypes")
    private static Object last(Collection c, boolean remove) {
        if (c.isEmpty()) throw new java.util.NoSuchElementException();
        if (c instanceof List) {
            List list = (List) c;
            return remove ? list.remove(list.size() - 1) : list.get(list.size() - 1);
        }
        Object value = null;
        for (Object o : c) value = o;
        if (remove) c.remove(value);
        return value;
    }

    private static void skipNBytes(InputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped > 0) {
                n -= skipped;
            } else {
                if (in.read() < 0) throw new java.io.EOFException();
                n--;
            }
        }
    }

    /** Nothing, zero, false, an empty list / set / map, null, or for a builder-style call its own object. */
    private static Object absent(Object receiver, String key) {
        String type = key.substring(key.lastIndexOf(key.indexOf('(') < 0 ? ':' : ')') + 1);
        switch (type) {
            case "V": return null;
            case "Z": return Boolean.FALSE;
            case "B": return (byte) 0;
            case "S": return (short) 0;
            case "C": return (char) 0;
            case "I": return 0;
            case "J": return 0L;
            case "F": return 0f;
            case "D": return 0d;
            case "Ljava/util/List;":
            case "Ljava/util/Collection;":
                return Collections.emptyList();
            case "Ljava/util/Set;":
                return Collections.emptySet();
            case "Ljava/util/Map;":
                return Collections.emptyMap();
            default: break;
        }
        if (receiver != null && type.startsWith("L")) {
            try {
                Class<?> returned = Class.forName(type.substring(1, type.length() - 1).replace('/', '.'), false,
                        Android13.class.getClassLoader());
                if (returned.isInstance(receiver)) return receiver;
            } catch (ReflectiveOperationException | LinkageError e) {
                // The return type itself is newer than the phone.
            }
        }
        return null;
    }
}
