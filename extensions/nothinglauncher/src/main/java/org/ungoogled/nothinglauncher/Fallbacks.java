package org.ungoogled.nothinglauncher;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * Hidden Android members Nothing Launcher needs a real answer for where Android refuses them. Everything else answers
 * "absent" (see Members). Only 2.2.8, the Android 13 Launcher3, needs any: its launcher activity starts with them (the
 * main thread's handler for the fold animation, the display, launch options); the answers are the other Launcher3
 * ports' (Moto App Launcher), from Android's public calls. The newer builds keep answering "absent".
 */
final class Fallbacks {
    static final Object NONE = new Object();
    private static Boolean olderLauncher3;

    private Fallbacks() {
    }

    /** Whether this is the Android 13 Launcher3 (2.2.8): its activity still has the BaseQuickstepLauncher base. */
    private static boolean olderLauncher3() {
        if (olderLauncher3 == null) {
            boolean found;
            try {
                Class.forName("com.android.launcher3.BaseQuickstepLauncher", false, Fallbacks.class.getClassLoader());
                found = true;
            } catch (ClassNotFoundException | LinkageError e) {
                found = false;
            }
            olderLauncher3 = found;
        }
        return olderLauncher3;
    }

    static Object call(Object receiver, String key, Object[] args) {
        if (!olderLauncher3()) return NONE;
        try {
            switch (key) {
                // The app's own user, display and main thread.
                case "Landroid/app/Service;->getUserId()I":
                    // A UserHandle's hash is its user id.
                    return Process.myUserHandle().hashCode();
                case "Landroid/app/Activity;->getDisplayId()I": {
                    android.view.Display d = Windows.getDisplay((Context) receiver);
                    return d == null ? android.view.Display.DEFAULT_DISPLAY : d.getDisplayId();
                }
                case "Landroid/app/Activity;->getMainThreadHandler()Landroid/os/Handler;":
                    return new Handler(Looper.getMainLooper());
                case "Landroid/view/Display;->getUniqueId()Ljava/lang/String;":
                    return "display:" + ((android.view.Display) receiver).getDisplayId();
                // Launches with the launcher's own remote animation, which only a privileged home app may run:
                // Android's own launch animation instead.
                case "Landroid/app/ActivityOptions;->makeRemoteAnimation(Landroid/view/RemoteAnimationAdapter;Landroid/window/RemoteTransition;)Landroid/app/ActivityOptions;":
                case "Landroid/app/ActivityOptions;->makeRemoteTransition(Landroid/window/RemoteTransition;)Landroid/app/ActivityOptions;":
                case "Landroid/app/ActivityOptions;->makeCustomTaskAnimation(Landroid/content/Context;IILandroid/os/Handler;Landroid/app/ActivityOptions$OnAnimationStartedListener;Landroid/app/ActivityOptions$OnAnimationFinishedListener;)Landroid/app/ActivityOptions;":
                    return android.app.ActivityOptions.makeBasic();
                // Geometry and math helpers.
                case "Landroid/view/MotionEvent;->applyTransform(Landroid/graphics/Matrix;)V":
                    ((android.view.MotionEvent) receiver).transform((Matrix) args[0]);
                    return null;
                case "Landroid/graphics/Rect;->inset(Landroid/graphics/Rect;)V": {
                    Rect r = (Rect) args[0];
                    ((Rect) receiver).inset(r.left, r.top, r.right, r.bottom);
                    return null;
                }
                case "Landroid/graphics/Insets;->toRect()Landroid/graphics/Rect;": {
                    android.graphics.Insets i = (android.graphics.Insets) receiver;
                    return new Rect(i.left, i.top, i.right, i.bottom);
                }
                case "Landroid/content/res/ResourceId;->isValid(I)Z":
                    return (Integer) args[0] != -1 && ((Integer) args[0] & 0xff000000) != 0 && ((Integer) args[0] & 0x00ff0000) != 0;
                case "Landroid/util/MathUtils;->max(FF)F":
                    return Math.max((Float) args[0], (Float) args[1]);
                case "Landroid/util/MathUtils;->log(F)F":
                    return (float) Math.log((Float) args[0]);
                case "Landroid/util/MathUtils;->saturate(F)F":
                    return Math.max(0f, Math.min(1f, (Float) args[0]));
                case "Landroid/util/MathUtils;->constrainedMap(FFFFF)F": {
                    float rangeMin = (Float) args[0], rangeMax = (Float) args[1], valueMin = (Float) args[2],
                            valueMax = (Float) args[3], value = (Float) args[4];
                    float t = valueMax == valueMin ? 0f : (value - valueMin) / (valueMax - valueMin);
                    t = Math.max(0f, Math.min(1f, t));
                    return rangeMin + (rangeMax - rangeMin) * t;
                }
                default:
                    return NONE;
            }
        } catch (RuntimeException e) {
            return NONE;
        }
    }

    static Object get(String key) {
        return NONE;
    }

    static Object construct(String key, Object[] args) {
        return NONE;
    }
}
