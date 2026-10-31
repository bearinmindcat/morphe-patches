package org.ungoogled.samsungroutines;

import android.app.Application;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.provider.Settings;

import com.samsung.android.os.SemCompanionDeviceBatteryInfo;

import java.util.ArrayList;

/**
 * One UI members Modes and Routines needs a real answer for off One UI, from Android's own public calls. Everything
 * else a phone lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    /** One UI's brightness settings range; Android's screen_brightness setting has the same. */
    private static final int MIN_BRIGHTNESS = 1;
    private static final int MAX_BRIGHTNESS = 255;

    private Fallbacks() {
    }

    private static int myUser() {
        return Process.myUid() / 100000;
    }

    /** A real user id other than this app's (negative ids mean "current" and the like). */
    private static boolean otherUser(int id) {
        return id >= 0 && id != myUser();
    }

    private static UserHandle user(int id) {
        return UserHandle.getUserHandleForUid(id * 100000);
    }

    static Application app() {
        try {
            return (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Throws a checked exception (NameNotFoundException) from a call made through reflection. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                case "Landroid/os/UserHandle;->semGetCallingUserId()I":
                case "Landroid/app/ActivityManager;->semGetCurrentUser()I":
                    return Process.myUid() / 100000;
                case "Landroid/os/UserHandle;->semGetIdentifier()I":
                    // UserHandle's hash is its user id.
                    return receiver.hashCode();
                case "Landroid/os/UserHandle;->semOf(I)Landroid/os/UserHandle;":
                    return user((Integer) args[0]);
                case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                case "Landroid/os/VibrationEffect;->semCreateWaveform(II)Landroid/os/VibrationEffect;": {
                    int repeat = (Integer) args[1];
                    return VibrationEffect.createWaveform(new long[]{0, 500, 500}, repeat >= 0 ? 0 : -1);
                }
                case "Landroid/os/PowerManager;->semGetMinimumScreenBrightnessSetting()I":
                    return MIN_BRIGHTNESS;
                case "Landroid/os/PowerManager;->semGetMaximumScreenBrightnessSetting()I":
                    return MAX_BRIGHTNESS;
                case "Landroid/hardware/display/DisplayManager;->semGetBrightness(I)F": {
                    Application app = app();
                    if (app == null) return NONE;
                    int value = SettingsAccess.systemGetInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, MAX_BRIGHTNESS / 2);
                    return Math.max(0f, Math.min(1f, (value - MIN_BRIGHTNESS) / (float) (MAX_BRIGHTNESS - MIN_BRIGHTNESS)));
                }
                case "Landroid/hardware/display/DisplayManager;->semSetBrightness(IF)V": {
                    // One UI sets the display's brightness directly; elsewhere the setting does it ("Modify system settings").
                    Application app = app();
                    if (app == null) return null;
                    float f = Math.max(0f, Math.min(1f, (Float) args[1]));
                    SettingsAccess.systemPutInt(app.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                            Math.round(MIN_BRIGHTNESS + f * (MAX_BRIGHTNESS - MIN_BRIGHTNESS)));
                    return null;
                }
                case "Landroid/content/pm/PackageManager;->semGetPackageInfoAsUser(Ljava/lang/String;II)Landroid/content/pm/PackageInfo;":
                    // Only this user's apps are visible (One UI's Dual Messenger user 95 does not exist here).
                    if (otherUser((Integer) args[2])) throw sneaky(new PackageManager.NameNotFoundException((String) args[0]));
                    try {
                        return ((PackageManager) receiver).getPackageInfo((String) args[0], (Integer) args[1]);
                    } catch (PackageManager.NameNotFoundException e) {
                        throw sneaky(e);
                    }
                case "Landroid/content/pm/PackageManager;->semQueryIntentActivitiesAsUser(Landroid/content/Intent;II)Ljava/util/List;":
                    if (otherUser((Integer) args[2])) return new ArrayList<>();
                    return ((PackageManager) receiver).queryIntentActivities((Intent) args[0], (Integer) args[1]);
                case "Landroid/content/pm/PackageManager;->semGetActivityIconForIconTray(Landroid/content/ComponentName;I)Landroid/graphics/drawable/Drawable;":
                    try {
                        return ((PackageManager) receiver).getActivityIcon((ComponentName) args[0]);
                    } catch (PackageManager.NameNotFoundException e) {
                        throw sneaky(e);
                    }
                case "Landroid/content/pm/PackageManager;->semGetDrawableForIconTray(Landroid/graphics/drawable/Drawable;I)Landroid/graphics/drawable/Drawable;":
                    return (Drawable) args[0];
                case "Landroid/media/AudioManager;->semGetRingerModeInternal()I":
                    return ((AudioManager) receiver).getRingerMode();
                case "Landroid/app/KeyguardManager;->semIsKeyguardShowingAndNotOccluded()Z":
                    return ((KeyguardManager) receiver).isKeyguardLocked();
                case "Landroid/content/ClipData;->semGetItems()Ljava/util/ArrayList;": {
                    ClipData clip = (ClipData) receiver;
                    ArrayList<ClipData.Item> items = new ArrayList<>();
                    for (int i = 0; i < clip.getItemCount(); i++) items.add(clip.getItemAt(i));
                    return items;
                }
                case "Landroid/os/BatteryManager;->semGetDeviceBatteryInfos()[Lcom/samsung/android/os/SemCompanionDeviceBatteryInfo;":
                    return new SemCompanionDeviceBatteryInfo[0];
                case "Landroid/os/UserManager;->semGetUsers()Ljava/util/List;":
                    return new ArrayList<>();
                case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                    return SettingsAccess.systemGetInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
            }
        } catch (SecurityException | ClassCastException e) {
            return NONE;
        }
        return NONE;
    }

    static Object get(String key) {
        switch (key) {
            case "Landroid/os/UserHandle;->SEM_ALL:Landroid/os/UserHandle;":
                return user(-1);
            case "Landroid/os/UserHandle;->SEM_OWNER:Landroid/os/UserHandle;":
                return user(0);
            case "Landroid/os/UserHandle;->SEM_CURRENT:Landroid/os/UserHandle;":
                return Process.myUserHandle();
        }
        return NONE;
    }
}
