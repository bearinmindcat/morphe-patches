package org.ungoogled.samsunglauncher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationEffect;

import java.util.ArrayList;

/**
 * One UI members One UI Home needs a real answer for off One UI, from Android's own public calls: app icons ("icon
 * tray" variants), users, package lookups and settings reads. Everything else a phone lacks answers "absent" (see
 * OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    private static UserHandle user(int id) {
        return UserHandle.getUserHandleForUid(id * 100000);
    }

    /** Activity's own (hidden, but open to apps) isResumed(); where refused, whether its window has focus. */
    private static boolean isResumed(Activity activity) {
        try {
            java.lang.reflect.Method method = Activity.class.getDeclaredMethod("isResumed");
            method.setAccessible(true);
            return (Boolean) method.invoke(activity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return activity.hasWindowFocus();
        }
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                case "Landroid/app/ActivityManager;->semGetCurrentUser()I":
                    return Process.myUid() / 100000;
                case "Landroid/os/UserHandle;->semGetCallingUserId()I":
                    return Binder.getCallingUid() / 100000;
                case "Landroid/os/UserHandle;->semGetIdentifier()I":
                    // A UserHandle's hash is its user id.
                    return receiver.hashCode();
                case "Landroid/os/UserHandle;->semGetUserId(I)I":
                    return (Integer) args[0] / 100000;
                case "Landroid/os/UserHandle;->semOf(I)Landroid/os/UserHandle;":
                    return user((Integer) args[0]);
                case "Landroid/os/UserManager;->semGetUsers()Ljava/util/List;":
                    return new ArrayList<>();
                case "Landroid/content/pm/LauncherActivityInfo;->semGetBadgedIconForIconTray(I)Landroid/graphics/drawable/Drawable;":
                    return ((LauncherActivityInfo) receiver).getBadgedIcon((Integer) args[0]);
                case "Landroid/content/pm/PackageManager;->semGetActivityIconForIconTray(Landroid/content/ComponentName;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getActivityIcon((ComponentName) args[0]);
                case "Landroid/content/pm/PackageManager;->semGetApplicationIconForIconTray(Landroid/content/pm/ApplicationInfo;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getApplicationIcon((ApplicationInfo) args[0]);
                case "Landroid/content/pm/PackageManager;->semGetApplicationIconForIconTray(Ljava/lang/String;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getApplicationIcon((String) args[0]);
                case "Landroid/content/pm/PackageManager;->semGetDrawableForIconTray(Landroid/graphics/drawable/Drawable;I)Landroid/graphics/drawable/Drawable;":
                    return (Drawable) args[0];
                case "Landroid/content/pm/PackageManager;->semGetPackageInfoAsUser(Ljava/lang/String;II)Landroid/content/pm/PackageInfo;":
                    return ((PackageManager) receiver).getPackageInfo((String) args[0], (Integer) args[1]);
                case "Landroid/content/pm/PackageManager;->semQueryIntentActivitiesAsUser(Landroid/content/Intent;II)Ljava/util/List;":
                    return ((PackageManager) receiver).queryIntentActivities((Intent) args[0], (Integer) args[1]);
                case "Landroid/content/Context;->semCreatePackageContextAsUser(Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/Context;":
                    return ((Context) receiver).createPackageContext((String) args[0], (Integer) args[1]);
                case "Landroid/content/Context;->semRegisterReceiverAsUser(Landroid/content/BroadcastReceiver;Landroid/os/UserHandle;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;I)Landroid/content/Intent;": {
                    int flags = (Integer) args[5];
                    if ((flags & (Context.RECEIVER_EXPORTED | Context.RECEIVER_NOT_EXPORTED)) == 0) {
                        flags |= Context.RECEIVER_EXPORTED;
                    }
                    return ((Context) receiver).registerReceiver((BroadcastReceiver) args[0], (IntentFilter) args[2],
                            (String) args[3], (Handler) args[4], flags);
                }
                case "Landroid/content/Context;->semStartServiceAsUser(Landroid/content/Intent;Landroid/os/UserHandle;)Landroid/content/ComponentName;":
                    return ((Context) receiver).startService((Intent) args[0]);
                case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                    return LocalSettings.systemGetInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
                case "Landroid/provider/Settings$System;->semGetFloatForUser(Landroid/content/ContentResolver;Ljava/lang/String;FI)F":
                    return LocalSettings.systemGetFloat((ContentResolver) args[0], (String) args[1], (Float) args[2]);
                case "Landroid/content/ClipData;->semGetItems()Ljava/util/ArrayList;": {
                    ClipData clip = (ClipData) receiver;
                    ArrayList<ClipData.Item> items = new ArrayList<>();
                    for (int i = 0; i < clip.getItemCount(); i++) items.add(clip.getItemAt(i));
                    return items;
                }
                case "Landroid/appwidget/AppWidgetHost;->semStartAppWidgetConfigureActivityForResult(Landroid/app/Activity;IIILandroid/os/Bundle;)V":
                    ((AppWidgetHost) receiver).startAppWidgetConfigureActivityForResult((Activity) args[0],
                            (Integer) args[1], (Integer) args[2], (Integer) args[3], (Bundle) args[4]);
                    return null;
                case "Landroid/app/Activity;->semIsResumed()Z":
                    return isResumed((Activity) receiver);
                case "Landroid/app/Activity;->semOverridePendingTransition(II)V":
                    ((Activity) receiver).overridePendingTransition((Integer) args[0], (Integer) args[1]);
                    return null;
                case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                    // One UI's haptic patterns: Android's own light tick (the caller vibrates whatever it gets).
                    return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            }
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (SecurityException | ClassCastException e) {
            return NONE;
        }
        return NONE;
    }

    /** Objects of classes an installed app may not construct, from their public equivalents. */
    static Object construct(String key, Object[] args) {
        switch (key) {
            case "Landroid/hardware/devicestate/DeviceStateManager;-><init>()V": {
                Context c = App.context();
                return c == null ? null : c.getSystemService("device_state");
            }
        }
        return NONE;
    }

    static Object get(String key) {
        switch (key) {
            case "Landroid/os/UserHandle;->SEM_ALL:Landroid/os/UserHandle;":
                return user(-1);
            case "Landroid/os/UserHandle;->SEM_CURRENT:Landroid/os/UserHandle;":
                return user(-2);
        }
        return NONE;
    }
}
