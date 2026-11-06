package org.ungoogled.sonylauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.view.View;

/**
 * Hidden members the launcher needs a real answer for on a phone that refuses them, from Android's own public calls:
 * the app's user and display, component names, a view's scroll position and bounds, content observers, widget
 * listeners. Everything else answers "absent" (see Members).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    private static int myUserId() {
        // A UserHandle's hash is its user id.
        return Process.myUserHandle().hashCode();
    }

    private static int displayId(Context c) {
        android.view.Display d = Windows.getDisplay(c);
        return d == null ? android.view.Display.DEFAULT_DISPLAY : d.getDisplayId();
    }

    static Object call(Object receiver, String key, Object[] args) {
        // A configuration's window configuration (hidden; the launcher reads it through Configuration's hidden field,
        // which an app gets as null): the full-screen bounds and rotation of the default display (see Reflect).
        if (key.startsWith("Landroid/app/WindowConfiguration;->") && (args == null || args.length == 0)) {
            try {
                return Reflect.WindowConfiguration.class.getMethod(key.substring(key.indexOf("->") + 2, key.indexOf('(')))
                        .invoke(Reflect.windowConfiguration);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return NONE;
            }
        }
        try {
            switch (key) {
                // The app's own user and display.
                case "Landroid/content/Context;->getUser()Landroid/os/UserHandle;":
                case "Landroid/app/Activity;->getUser()Landroid/os/UserHandle;":
                case "Landroid/content/ContextWrapper;->getUser()Landroid/os/UserHandle;":
                    return Process.myUserHandle();
                case "Landroid/content/Context;->getUserId()I":
                case "Landroid/app/Activity;->getUserId()I":
                case "Landroid/content/ContextWrapper;->getUserId()I":
                case "Landroid/os/UserManager;->getProcessUserId()I":
                    return myUserId();
                case "Landroid/content/Context;->getDisplayId()I":
                case "Landroid/content/ContextWrapper;->getDisplayId()I":
                case "Landroid/app/Activity;->getDisplayId()I":
                    return displayId((Context) receiver);
                case "Landroid/content/Context;->getMainThreadHandler()Landroid/os/Handler;":
                case "Landroid/app/Activity;->getMainThreadHandler()Landroid/os/Handler;":
                    return new Handler(Looper.getMainLooper());
                // Launches with the launcher's own remote animation, which only a privileged home app may run:
                // Android's own launch animation instead.
                case "Landroid/app/ActivityOptions;->makeRemoteAnimation(Landroid/view/RemoteAnimationAdapter;Landroid/window/RemoteTransition;)Landroid/app/ActivityOptions;":
                case "Landroid/app/ActivityOptions;->makeRemoteAnimation(Landroid/view/RemoteAnimationAdapter;)Landroid/app/ActivityOptions;":
                case "Landroid/app/ActivityOptions;->makeRemoteTransition(Landroid/window/RemoteTransition;)Landroid/app/ActivityOptions;":
                case "Landroid/app/ActivityOptions;->makeCustomTaskAnimation(Landroid/content/Context;IILandroid/os/Handler;Landroid/app/ActivityOptions$OnAnimationStartedListener;Landroid/app/ActivityOptions$OnAnimationFinishedListener;)Landroid/app/ActivityOptions;":
                    return android.app.ActivityOptions.makeBasic();
                // Spring animations' frames (see Frames).
                case "Landroid/animation/AnimationHandler;->addAnimationFrameCallback(Landroid/animation/AnimationHandler$AnimationFrameCallback;J)V":
                    Frames.add(args[0], (Long) args[1]);
                    return null;
                case "Landroid/animation/AnimationHandler;->removeCallback(Landroid/animation/AnimationHandler$AnimationFrameCallback;)V":
                    Frames.remove(args[0]);
                    return null;
                case "Landroid/animation/AnimationHandler;->getAnimationCount()I":
                    return Frames.count();
                // Touch and animation helpers, from their public equivalents.
                case "Landroid/view/VelocityTracker;->obtain(I)Landroid/view/VelocityTracker;":
                    return android.view.VelocityTracker.obtain();
                case "Landroid/view/MotionEvent;->getDisplayId()I":
                    return android.view.Display.DEFAULT_DISPLAY;
                case "Landroid/view/MotionEvent;->getXDispatchLocation(I)F":
                    return ((android.view.MotionEvent) receiver).getX((Integer) args[0]);
                case "Landroid/view/MotionEvent;->getYDispatchLocation(I)F":
                    return ((android.view.MotionEvent) receiver).getY((Integer) args[0]);
                case "Landroid/view/MotionEvent;->isTouchEvent()Z": {
                    android.view.MotionEvent e = (android.view.MotionEvent) receiver;
                    if (!e.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) return false;
                    int a = e.getActionMasked();
                    return a != android.view.MotionEvent.ACTION_HOVER_ENTER && a != android.view.MotionEvent.ACTION_HOVER_MOVE
                            && a != android.view.MotionEvent.ACTION_HOVER_EXIT && a != android.view.MotionEvent.ACTION_SCROLL
                            && a != android.view.MotionEvent.ACTION_BUTTON_PRESS && a != android.view.MotionEvent.ACTION_BUTTON_RELEASE;
                }
                case "Landroid/view/MotionEvent;->isHoverEvent()Z": {
                    int a = ((android.view.MotionEvent) receiver).getActionMasked();
                    return a == android.view.MotionEvent.ACTION_HOVER_ENTER || a == android.view.MotionEvent.ACTION_HOVER_MOVE
                            || a == android.view.MotionEvent.ACTION_HOVER_EXIT;
                }
                case "Landroid/view/MotionEvent;->isStylusPointer()Z":
                    return ((android.view.MotionEvent) receiver).getToolType(0) == android.view.MotionEvent.TOOL_TYPE_STYLUS;
                case "Landroid/view/MotionEvent;->applyTransform(Landroid/graphics/Matrix;)V":
                    ((android.view.MotionEvent) receiver).transform((Matrix) args[0]);
                    return null;
                case "Landroid/view/ViewConfiguration;->getHoverTapSlop()I":
                    return ((android.view.ViewConfiguration) receiver).getScaledTouchSlop();
                case "Landroid/widget/FrameLayout;->getOnLongClickListener()Landroid/view/View$OnLongClickListener;":
                    return null;
                case "Landroid/animation/ValueAnimator;->canReverse()Z":
                    return true;
                // Handlers and messages.
                case "Landroid/os/Handler;->getMain()Landroid/os/Handler;":
                    return new Handler(Looper.getMainLooper());
                case "Landroid/os/HandlerThread;->getThreadHandler()Landroid/os/Handler;":
                    return new Handler(((android.os.HandlerThread) receiver).getLooper());
                case "Landroid/os/Handler;->hasEqualMessages(ILjava/lang/Object;)Z":
                    return ((Handler) receiver).hasMessages((Integer) args[0], args[1]);
                case "Landroid/os/Handler;->removeEqualMessages(ILjava/lang/Object;)V":
                    ((Handler) receiver).removeMessages((Integer) args[0], args[1]);
                    return null;
                case "Landroid/os/Handler;->removeCallbacksAndEqualMessages(Ljava/lang/Object;)V":
                    ((Handler) receiver).removeCallbacksAndMessages(args[0]);
                    return null;
                case "Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;IJ)Z": {
                    android.os.Message m = android.os.Message.obtain((Handler) receiver, (Runnable) args[0]);
                    m.what = (Integer) args[1];
                    return ((Handler) receiver).sendMessageDelayed(m, (Long) args[2]);
                }
                case "Landroid/os/Message;->setWhat(I)Landroid/os/Message;":
                    ((android.os.Message) receiver).what = (Integer) args[0];
                    return receiver;
                // Geometry.
                case "Landroid/graphics/Rect;->inset(Landroid/graphics/Rect;)V": {
                    Rect r = (Rect) args[0];
                    ((Rect) receiver).inset(r.left, r.top, r.right, r.bottom);
                    return null;
                }
                case "Landroid/graphics/Rect;->intersectUnchecked(Landroid/graphics/Rect;)V": {
                    Rect a = (Rect) receiver, b = (Rect) args[0];
                    a.set(Math.max(a.left, b.left), Math.max(a.top, b.top), Math.min(a.right, b.right), Math.min(a.bottom, b.bottom));
                    return null;
                }
                case "Landroid/graphics/Rect;->isValid()Z": {
                    Rect r = (Rect) receiver;
                    return r.left <= r.right && r.top <= r.bottom;
                }
                case "Landroid/graphics/RectF;->scale(F)V": {
                    RectF r = (RectF) receiver;
                    float f = (Float) args[0];
                    if (f != 1f) r.set(r.left * f, r.top * f, r.right * f, r.bottom * f);
                    return null;
                }
                case "Landroid/graphics/Point;->set(Landroid/graphics/Point;)V":
                    ((android.graphics.Point) receiver).set(((android.graphics.Point) args[0]).x, ((android.graphics.Point) args[0]).y);
                    return null;
                case "Landroid/graphics/Insets;->toRect()Landroid/graphics/Rect;": {
                    android.graphics.Insets i = (android.graphics.Insets) receiver;
                    return new Rect(i.left, i.top, i.right, i.bottom);
                }
                case "Landroid/graphics/Region;->obtain()Landroid/graphics/Region;":
                    return new android.graphics.Region();
                // Unclipped layers (the drawer's fading list edges): a full layer, and on restore the paint (a
                // DST_OUT fade) drawn over the region the layer was asked for, as Android does.
                case "Landroid/graphics/Canvas;->saveUnclippedLayer(IIII)I":
                    return Layers.save((android.graphics.Canvas) receiver, (Integer) args[0], (Integer) args[1],
                            (Integer) args[2], (Integer) args[3]);
                case "Landroid/graphics/Canvas;->restoreUnclippedLayer(ILandroid/graphics/Paint;)V":
                    Layers.restore((android.graphics.Canvas) receiver, (Integer) args[0], (android.graphics.Paint) args[1]);
                    return null;
                // A view's fading edge geometry.
                case "Landroid/view/ViewGroup;->getFadeTop(Z)I":
                case "Landroid/view/View;->getFadeTop(Z)I":
                    return ((View) receiver).getPaddingTop();
                case "Landroid/view/ViewGroup;->getFadeHeight(Z)I":
                case "Landroid/view/View;->getFadeHeight(Z)I": {
                    View v = (View) receiver;
                    return v.getHeight() - v.getPaddingTop() - v.getPaddingBottom();
                }
                case "Landroid/view/ViewGroup;->getFadingEdgeLength()I":
                case "Landroid/view/View;->getFadingEdgeLength()I":
                    return ((View) receiver).getVerticalFadingEdgeLength();
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
                case "Landroid/util/RotationUtils;->deltaRotation(II)I": {
                    int d = (Integer) args[1] - (Integer) args[0];
                    return d < 0 ? d + 4 : d;
                }
                case "Landroid/view/WindowManager;->fixScale(F)F":
                    return Math.max(0f, Math.min(20f, (Float) args[0]));
                case "Landroid/content/res/ResourceId;->isValid(I)Z":
                    return (Integer) args[0] != -1 && ((Integer) args[0] & 0xff000000) != 0 && ((Integer) args[0] & 0x00ff0000) != 0;
                case "Landroid/os/Build;->isDebuggable()Z":
                    return false;
                case "Landroid/view/WindowInsets$Type;->defaultVisible()I":
                    return android.view.WindowInsets.Type.systemBars();
                // Displays: an id unique per display, the smaller side in dp.
                case "Landroid/view/Display;->getUniqueId()Ljava/lang/String;":
                    return "display:" + ((android.view.Display) receiver).getDisplayId();
                case "Landroid/view/Display;->getMinSizeDimensionDp()F": {
                    android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                    ((android.view.Display) receiver).getRealMetrics(m);
                    return Math.min(m.widthPixels, m.heightPixels) / m.density;
                }
                // Component names.
                case "Landroid/content/pm/ComponentInfo;->getComponentName()Landroid/content/ComponentName;":
                case "Landroid/content/pm/ActivityInfo;->getComponentName()Landroid/content/ComponentName;": {
                    ComponentInfo info = (ComponentInfo) receiver;
                    return new ComponentName(info.packageName, info.name);
                }
                case "Landroid/content/pm/ResolveInfo;->getComponentInfo()Landroid/content/pm/ComponentInfo;": {
                    ResolveInfo info = (ResolveInfo) receiver;
                    if (info.activityInfo != null) return info.activityInfo;
                    if (info.serviceInfo != null) return info.serviceInfo;
                    return info.providerInfo;
                }
                case "Landroid/content/pm/PackageManager;->getInstalledApplicationsAsUser(II)Ljava/util/List;":
                    return ((PackageManager) receiver).getInstalledApplications((Integer) args[0]);
                case "Landroid/content/pm/ShortcutInfo;->getUserId()I":
                    return ((ShortcutInfo) receiver).getUserHandle().hashCode();
                case "Landroid/content/pm/ShortcutInfo;->getLabel()Ljava/lang/CharSequence;": {
                    ShortcutInfo info = (ShortcutInfo) receiver;
                    return info.getShortLabel() != null ? info.getShortLabel() : info.getLongLabel();
                }
                case "Landroid/graphics/drawable/Icon;->createWithResource(Landroid/content/res/Resources;I)Landroid/graphics/drawable/Icon;": {
                    Resources res = (Resources) args[0];
                    int id = (Integer) args[1];
                    return Icon.createWithResource(res.getResourcePackageName(id), id);
                }
                case "Landroid/content/Intent;->toShortString(ZZZZ)Ljava/lang/String;":
                    return String.valueOf(receiver);
                // Content observers for the app's own user: Android's public registration.
                case "Landroid/content/ContentResolver;->registerContentObserver(Landroid/net/Uri;ZLandroid/database/ContentObserver;I)V":
                    Calls.registerContentObserver((ContentResolver) receiver, (Uri) args[0], (Boolean) args[1],
                            (ContentObserver) args[2]);
                    return null;
                case "Landroid/provider/Settings$Secure;->getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)I":
                    return LocalSettings.secureGetInt((ContentResolver) args[0], (String) args[1], 0);
                case "Landroid/provider/Settings$System;->getLongForUser(Landroid/content/ContentResolver;Ljava/lang/String;JI)J":
                    return LocalSettings.systemGetLong((ContentResolver) args[0], (String) args[1], (Long) args[2]);
                // A view's bounds and transform.
                case "Landroid/view/View;->getBoundsOnScreen(Landroid/graphics/RectF;Z)V": {
                    View v = (View) receiver;
                    int[] at = new int[2];
                    v.getLocationOnScreen(at);
                    ((RectF) args[0]).set(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
                    return null;
                }
                case "Landroid/view/View;->getBoundsInWindow(Landroid/graphics/Rect;Z)V":
                case "Landroid/widget/TextView;->getBoundsInWindow(Landroid/graphics/Rect;Z)V": {
                    View v = (View) receiver;
                    int[] at = new int[2];
                    v.getLocationInWindow(at);
                    ((Rect) args[0]).set(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
                    return null;
                }
                case "Landroid/view/View;->getInverseMatrix()Landroid/graphics/Matrix;": {
                    Matrix inverse = new Matrix();
                    ((View) receiver).getMatrix().invert(inverse);
                    return inverse;
                }
                case "Landroid/view/View;->setFrame(IIII)Z": {
                    View v = (View) receiver;
                    boolean changed = v.getLeft() != (Integer) args[0] || v.getTop() != (Integer) args[1]
                            || v.getRight() != (Integer) args[2] || v.getBottom() != (Integer) args[3];
                    v.setLeftTopRightBottom((Integer) args[0], (Integer) args[1], (Integer) args[2], (Integer) args[3]);
                    return changed;
                }
                // A widget's own context (the launcher's custom widget views ask their super class for it).
                case "Landroid/appwidget/AppWidgetHostView;->getRemoteContextEnsuringCorrectCachedApkPath()Landroid/content/Context;": {
                    android.appwidget.AppWidgetHostView v = (android.appwidget.AppWidgetHostView) receiver;
                    android.appwidget.AppWidgetProviderInfo info = v.getAppWidgetInfo();
                    if (info == null || info.provider == null) return v.getContext();
                    return v.getContext().createPackageContext(info.provider.getPackageName(), Context.CONTEXT_RESTRICTED);
                }
            }
        } catch (Resources.NotFoundException | PackageManager.NameNotFoundException e) {
            return null;
        } catch (SecurityException | ClassCastException | NullPointerException e) {
            return NONE;
        }
        return NONE;
    }

    /** Objects of classes an installed app may not construct, from their public equivalents. */
    static Object construct(String key, Object[] args) {
        switch (key) {
            // A display's info (hidden constructor): the default display's own (its open mDisplayInfo field), which
            // the launcher then only reads (its getDisplayInfo fill-in is hidden too).
            case "Landroid/view/DisplayInfo;-><init>()V":
                return Reflect.defaultDisplayInfo();
        }
        return NONE;
    }

    static Object get(Object receiver, String key) {
        switch (key) {
            // A view's scroll position (the hidden fields behind getScrollX/Y).
            case "Landroid/view/ViewGroup;->mScrollX:I":
            case "Landroid/widget/FrameLayout;->mScrollX:I":
            case "Landroid/widget/LinearLayout;->mScrollX:I":
            case "Landroid/view/View;->mScrollX:I":
                return receiver instanceof View ? ((View) receiver).getScrollX() : NONE;
            case "Landroid/view/ViewGroup;->mScrollY:I":
            case "Landroid/widget/FrameLayout;->mScrollY:I":
            case "Landroid/widget/LinearLayout;->mScrollY:I":
            case "Landroid/view/View;->mScrollY:I":
                return receiver instanceof View ? ((View) receiver).getScrollY() : NONE;
            // A view group's position (the hidden fields behind getLeft/getRight).
            case "Landroid/view/ViewGroup;->mLeft:I":
                return receiver instanceof View ? ((View) receiver).getLeft() : NONE;
            case "Landroid/view/ViewGroup;->mRight:I":
                return receiver instanceof View ? ((View) receiver).getRight() : NONE;
            case "Landroid/view/ViewGroup;->mTop:I":
                return receiver instanceof View ? ((View) receiver).getTop() : NONE;
            case "Landroid/view/ViewGroup;->mBottom:I":
                return receiver instanceof View ? ((View) receiver).getBottom() : NONE;
            // A user build of the phone's own release.
            case "Landroid/os/Build;->IS_USER:Z":
                return true;
            case "Landroid/os/Build$VERSION;->DEVICE_INITIAL_SDK_INT:I":
            case "Landroid/os/Build$VERSION;->RESOURCES_SDK_INT:I":
                return android.os.Build.VERSION.SDK_INT;
            // The default display's info (see Reflect.defaultDisplayInfo), from Display's public calls.
            case "Landroid/view/DisplayInfo;->smallestNominalAppWidth:I":
            case "Landroid/view/DisplayInfo;->smallestNominalAppHeight:I":
            case "Landroid/view/DisplayInfo;->largestNominalAppWidth:I":
            case "Landroid/view/DisplayInfo;->largestNominalAppHeight:I": {
                android.view.Display d = Reflect.defaultDisplay();
                if (d == null) return NONE;
                android.graphics.Point small = new android.graphics.Point(), large = new android.graphics.Point();
                d.getCurrentSizeRange(small, large);
                boolean smallest = key.contains("smallest");
                boolean width = key.contains("Width");
                android.graphics.Point p = smallest ? small : large;
                return width ? p.x : p.y;
            }
            case "Landroid/view/DisplayInfo;->logicalDensityDpi:I": {
                android.view.Display d = Reflect.defaultDisplay();
                if (d == null) return NONE;
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                d.getRealMetrics(m);
                return m.densityDpi;
            }
            case "Landroid/view/DisplayInfo;->type:I":
                return 1; // Display.TYPE_INTERNAL
            case "Landroid/view/DisplayInfo;->modeId:I": {
                android.view.Display d = Reflect.defaultDisplay();
                return d == null ? NONE : d.getMode().getModeId();
            }
            case "Landroid/view/DisplayInfo;->displayCutout:Landroid/view/DisplayCutout;": {
                android.view.Display d = Reflect.defaultDisplay();
                return d == null ? NONE : d.getCutout();
            }
            case "Landroid/content/pm/ApplicationInfo;->iconRes:I":
                return receiver instanceof ApplicationInfo ? ((ApplicationInfo) receiver).icon : NONE;
            // Android's constants.
            case "Lcom/android/internal/accessibility/AccessibilityShortcutController;->ONE_HANDED_COMPONENT_NAME:Landroid/content/ComponentName;":
                return new ComponentName("com.android.server.accessibility", "OneHandedMode");
            case "Lcom/android/internal/util/ConcurrentUtils;->DIRECT_EXECUTOR:Ljava/util/concurrent/Executor;":
                return (java.util.concurrent.Executor) Runnable::run;
            case "Landroid/view/InsetsController;->RESIZE_INTERPOLATOR:Landroid/view/animation/Interpolator;":
                return new android.view.animation.LinearInterpolator();
        }
        return NONE;
    }
}
