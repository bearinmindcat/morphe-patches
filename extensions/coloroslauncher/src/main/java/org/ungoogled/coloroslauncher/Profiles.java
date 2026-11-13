package org.ungoogled.coloroslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Package lookups in the work profile. PackageManager answers another user's packages only to apps holding
 * INTERACT_ACROSS_USERS, which only system apps get: on a phone with a work profile the launcher's loader
 * (LaunchComponentAliasVerifier, OplusLauncherAppsCompat.getActivityList) and its badge loader died with a
 * SecurityException at every start, leaving a grey screen. LauncherApps gives the same answers for the app's own
 * profiles; any other user has nothing (an empty list, or NameNotFoundException as for a missing package).
 */
final class Profiles {
    private Profiles() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        switch (key) {
            case "Landroid/content/pm/PackageManager;->queryIntentActivitiesAsUser(Landroid/content/Intent;II)Ljava/util/List;":
                return activities((PackageManager) receiver, (Intent) args[0], (Integer) args[1], (Integer) args[2]);
            case "Landroid/content/pm/PackageManager;->queryIntentActivitiesAsUser(Landroid/content/Intent;ILandroid/os/UserHandle;)Ljava/util/List;":
                return activities((PackageManager) receiver, (Intent) args[0], (Integer) args[1], args[2].hashCode());
            case "Landroid/content/pm/PackageManager;->getPackageUidAsUser(Ljava/lang/String;I)I":
                return applicationInfo((String) args[0], 0, (Integer) args[1]).uid;
            case "Landroid/content/pm/PackageManager;->getPackageUidAsUser(Ljava/lang/String;II)I":
                return applicationInfo((String) args[0], (Integer) args[1], (Integer) args[2]).uid;
            case "Landroid/content/pm/PackageManager;->getApplicationInfoAsUser(Ljava/lang/String;II)Landroid/content/pm/ApplicationInfo;":
                return applicationInfo((String) args[0], (Integer) args[1], (Integer) args[2]);
            case "Landroid/content/pm/PackageManager;->getApplicationInfoAsUser(Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/pm/ApplicationInfo;":
                return applicationInfo((String) args[0], (Integer) args[1], args[2].hashCode());
        }
        return Fallbacks.NONE;
    }

    /** The user [id] if it is the app's own user or one of its profiles (a UserHandle's hash is its user id). */
    private static UserHandle profile(Context context, int id) {
        if (context == null) return null;
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null) return null;
        for (UserHandle user : users.getUserProfiles()) if (user.hashCode() == id) return user;
        return null;
    }

    private static List<ResolveInfo> activities(PackageManager packages, Intent intent, int flags, int id) {
        if (id == Process.myUserHandle().hashCode()) return packages.queryIntentActivities(intent, flags);
        List<ResolveInfo> found = new ArrayList<>();
        Context context = App.context();
        UserHandle user = profile(context, id);
        // LauncherApps lists a profile's launchable activities, which is what the launcher asks it for.
        if (user == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)) return found;
        ComponentName component = intent.getComponent();
        String pkg = intent.getPackage() != null ? intent.getPackage()
                : component != null ? component.getPackageName() : null;
        LauncherApps apps = context.getSystemService(LauncherApps.class);
        for (LauncherActivityInfo activity : apps.getActivityList(pkg, user)) {
            if (component != null && !component.equals(activity.getComponentName())) continue;
            ResolveInfo info = new ResolveInfo();
            info.activityInfo = activity.getActivityInfo();
            found.add(info);
        }
        return found;
    }

    private static ApplicationInfo applicationInfo(String pkg, int flags, int id) {
        Context context = App.context();
        UserHandle user = profile(context, id);
        try {
            if (user == null) throw new PackageManager.NameNotFoundException(pkg);
            ApplicationInfo info = context.getSystemService(LauncherApps.class).getApplicationInfo(pkg, flags, user);
            if (info == null) throw new PackageManager.NameNotFoundException(pkg);
            return info;
        } catch (PackageManager.NameNotFoundException e) {
            // The app catches PackageManager's own exception for a package that is not there.
            throw Profiles.<RuntimeException>sneaky(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
