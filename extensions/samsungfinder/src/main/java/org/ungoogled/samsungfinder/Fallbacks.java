package org.ungoogled.samsungfinder;

import android.app.Activity;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.view.ViewGroup;

/**
 * One UI members Finder needs a real answer for off One UI, from Android's own public calls. Everything else a phone
 * lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    /**
     * One UI's version number. Finder 10.2 ships with One UI 5 (Android 13); below that it takes older code paths:
     * off One UI it is given One UI 5.1's number for its own feature switches.
     */
    private static final int ONE_UI_5_1_PLATFORM = 140100;

    private Fallbacks() {
    }

    private static UserHandle user(int id) {
        return UserHandle.getUserHandleForUid(id * 100000);
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                    return Process.myUid() / 100000;
                case "Landroid/os/UserHandle;->semGetIdentifier()I":
                    // A UserHandle's hash is its user id.
                    return receiver.hashCode();
                case "Landroid/os/UserHandle;->semOf(I)Landroid/os/UserHandle;":
                    return user((Integer) args[0]);
                case "Landroid/view/ViewGroup$MarginLayoutParams;->semSetMarginsRelative(IIII)V": {
                    ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) receiver;
                    p.setMarginStart((Integer) args[0]);
                    p.topMargin = (Integer) args[1];
                    p.setMarginEnd((Integer) args[2]);
                    p.bottomMargin = (Integer) args[3];
                    return null;
                }
                case "Landroid/app/Activity;->semOverridePendingTransition(II)V":
                    ((Activity) receiver).overridePendingTransition((Integer) args[0], (Integer) args[1]);
                    return null;
                case "Landroid/content/pm/PackageManager;->semGetPackageInfoAsUser(Ljava/lang/String;II)Landroid/content/pm/PackageInfo;":
                    return ((PackageManager) receiver).getPackageInfo((String) args[0], (Integer) args[1]);
                case "Landroid/content/pm/PackageManager;->semQueryIntentActivitiesAsUser(Landroid/content/Intent;II)Ljava/util/List;":
                    return ((PackageManager) receiver).queryIntentActivities((Intent) args[0], (Integer) args[1]);
                case "Landroid/content/pm/PackageManager;->semGetApplicationIconForIconTray(Ljava/lang/String;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getApplicationIcon((String) args[0]);
                case "Landroid/content/pm/PackageManager;->semGetActivityIconForIconTray(Landroid/content/ComponentName;I)Landroid/graphics/drawable/Drawable;":
                    return ((PackageManager) receiver).getActivityIcon((ComponentName) args[0]);
                case "Landroid/content/pm/PackageManager;->semGetDrawableForIconTray(Landroid/graphics/drawable/Drawable;I)Landroid/graphics/drawable/Drawable;":
                    return (Drawable) args[0];
                case "Landroid/content/pm/LauncherActivityInfo;->semGetBadgedIconForIconTray(I)Landroid/graphics/drawable/Drawable;":
                    return ((LauncherActivityInfo) receiver).getBadgedIcon((Integer) args[0]);
                case "Landroid/content/Context;->semRegisterReceiverAsUser(Landroid/content/BroadcastReceiver;Landroid/os/UserHandle;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;":
                    return ((Context) receiver).registerReceiver((BroadcastReceiver) args[0], (IntentFilter) args[2],
                            (String) args[3], (Handler) args[4], Context.RECEIVER_EXPORTED);
                case "Landroid/app/SearchManager;->semGetSearchablesInInsightSearch(Z)Ljava/util/List;":
                    return Insight.searchables((SearchManager) receiver);
                case "Landroid/app/SearchableInfo;->semShouldIncludeInInsightSearch()Z":
                    return Insight.included((SearchableInfo) receiver);
                case "Landroid/app/SearchableInfo;->semGetSearchLayoutStyle()Ljava/lang/String;":
                    return Insight.layoutStyle((SearchableInfo) receiver);
                case "Landroid/app/SearchableInfo;->semGetLabelId()I":
                    return Insight.labelId((SearchableInfo) receiver);
            }
        } catch (PackageManager.NameNotFoundException | SecurityException | ClassCastException e) {
            return NONE;
        }
        return NONE;
    }

    static Object get(String key) {
        switch (key) {
            case "Landroid/os/Build$VERSION;->SEM_PLATFORM_INT:I":
                return ONE_UI_5_1_PLATFORM;
            case "Landroid/os/UserHandle;->SEM_ALL:Landroid/os/UserHandle;":
                return user(-1);
            case "Landroid/os/UserHandle;->SEM_CURRENT:Landroid/os/UserHandle;":
                return Process.myUserHandle();
        }
        return NONE;
    }
}
