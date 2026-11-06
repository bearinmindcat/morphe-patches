package org.ungoogled.samsungkeyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.os.Binder;
import android.os.Process;
import android.os.VibrationEffect;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;

/**
 * One UI members the keyboard needs a real answer for off One UI, from Android's own equivalents: the user it runs
 * as, relative margins (its layouts), the key press vibration (a null effect would throw), app lists and the like.
 * Everything else a phone lacks answers "absent" (see OneUi). Only asked where the phone does not have the member.
 */
final class Fallbacks {
    static final Object NONE = new Object();

    /** Android's users each own a range of this many uids. */
    private static final int PER_USER_RANGE = 100000;

    private Fallbacks() {
    }

    private static int myUserId() {
        return Process.myUserHandle().hashCode();
    }

    /** The key press feel: Android's own keyboard click, which every phone tunes for itself. */
    private static VibrationEffect click() {
        return VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
    }

    static Object call(Object receiver, String key, Object[] args) {
        int arrow = key.indexOf("->");
        String member = key.substring(arrow + 2);
        if (member.equals("semSetMarginsRelative(IIII)V") && receiver instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) receiver;
            p.setMarginStart((Integer) args[0]);
            p.topMargin = (Integer) args[1];
            p.setMarginEnd((Integer) args[2]);
            p.bottomMargin = (Integer) args[3];
            return null;
        }
        switch (key) {
            case "Landroid/os/UserHandle;->semGetMyUserId()I":
            case "Landroid/app/ActivityManager;->semGetCurrentUser()I":
                return myUserId();
            case "Landroid/os/UserHandle;->semGetUserId(I)I":
                return (Integer) args[0] / PER_USER_RANGE;
            case "Landroid/os/UserHandle;->semGetCallingUserId()I":
                return Binder.getCallingUid() / PER_USER_RANGE;
            case "Landroid/os/UserHandle;->semGetIdentifier()I":
                return receiver.hashCode();
            case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
            case "Landroid/os/VibrationEffect;->semCreateWaveform(II)Landroid/os/VibrationEffect;":
                return click();
            case "Landroid/view/HapticFeedbackConstants;->semGetVibrationIndex(I)I":
                return HapticFeedbackConstants.KEYBOARD_TAP;
            case "Landroid/graphics/Typeface;->semIsDefaultFontStyle()Z":
                // The phone's own font is the keyboard's default; One UI's font styles do not exist here.
                return true;
            case "Landroid/content/pm/PackageManager;->semQueryIntentActivitiesAsUser(Landroid/content/Intent;II)Ljava/util/List;":
                return ((PackageManager) receiver).queryIntentActivities((Intent) args[0], (Integer) args[1]);
            case "Landroid/media/AudioManager;->semGetSituationVolume(II)F":
                return 1f;
            case "Landroid/media/AudioDeviceInfo;->semGetAddress()Ljava/lang/String;":
                return ((AudioDeviceInfo) receiver).getAddress();
            case "Landroid/view/accessibility/AccessibilityManager;->semIsScreenReaderEnabled()Z":
                return ((AccessibilityManager) receiver).isTouchExplorationEnabled();
            case "Landroid/app/Activity;->semOverridePendingTransition(II)V":
                ((Activity) receiver).overridePendingTransition((Integer) args[0], (Integer) args[1]);
                return null;
            case "Landroid/content/Context;->semStartServiceAsUser(Landroid/content/Intent;Landroid/os/UserHandle;)Landroid/content/ComponentName;":
                return ((Context) receiver).startService((Intent) args[0]);
            case "Landroid/view/MotionEvent;->semObtain(JJII[Landroid/view/MotionEvent$PointerProperties;[Landroid/view/MotionEvent$PointerCoords;IIIFFIIII)Landroid/view/MotionEvent;":
                // (down, event, action, pointers, properties, coordinates, meta, buttons, display, x, y, device, edges,
                // source, flags); the caller places the event itself.
                return MotionEvent.obtain((Long) args[0], (Long) args[1], (Integer) args[2], (Integer) args[3],
                        (MotionEvent.PointerProperties[]) args[4], (MotionEvent.PointerCoords[]) args[5],
                        (Integer) args[6], (Integer) args[7], 1f, 1f, (Integer) args[11], (Integer) args[12],
                        (Integer) args[13], (Integer) args[14]);
        }
        return NONE;
    }

    /**
     * One UI's version numbers. Every phone this keyboard build runs on has One UI 5.0 or newer (Android 13 at least),
     * and below that it takes code paths Samsung never ships it with (no clipboard button, for one): off One UI it is
     * given One UI 5.0's numbers, the oldest it supports, for its own feature switches.
     */
    private static final int ONE_UI_5_PLATFORM = 140000;
    private static final int ONE_UI_5_SEM = 3301;

    /** The Android version the phone shipped with (Build.VERSION.DEVICE_INITIAL_SDK_INT, Android 12+). */
    private static int firstSdk() {
        try {
            return android.os.Build.VERSION.class.getField("DEVICE_INITIAL_SDK_INT").getInt(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return android.os.Build.VERSION.SDK_INT;
        }
    }

    static Object get(Object receiver, String key) {
        switch (key) {
            case "Landroid/os/Build$VERSION;->SEM_PLATFORM_INT:I":
                return ONE_UI_5_PLATFORM;
            case "Landroid/os/Build$VERSION;->SEM_INT:I":
                return ONE_UI_5_SEM;
            case "Landroid/os/Build$VERSION;->SEM_FIRST_SDK_INT:I":
                return firstSdk();
            case "Landroid/os/UserHandle;->SEM_ALL:Landroid/os/UserHandle;":
                // Its one use, a broadcast "to every user", goes to this user (as SystemAccess sends it on One UI).
                return Process.myUserHandle();
        }
        return NONE;
    }
}
