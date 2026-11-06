package org.ungoogled.samsungonehand;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Which app is in front. One Hand Operation + hides its handles in chosen apps and on the home screen; it learns the
 * app in front from a task stack listener and ActivityManager.getRunningTasks, both only the system may use (an app
 * sees its own tasks only). Here the accessibility service's window events say which activity came to the front
 * (package and class name, nothing of its content): getRunningTasks answers with that activity first, and the app's
 * task stack listener (kept by {@link #register}) is told the stack changed.
 */
public final class Tasks {
    private static final Set<Object> LISTENERS = new CopyOnWriteArraySet<>();
    private static final Map<String, Boolean> ACTIVITIES = new ConcurrentHashMap<>();
    private static volatile ComponentName front;

    private Tasks() {
    }

    /** The app's reflective registerTaskStackListener / unregisterTaskStackListener. */
    public static Object register(Object helper, Object service, String name, Class<?>[] types, Object[] args) {
        if (args == null || args.length != 1 || args[0] == null) return null;
        if (name != null && name.startsWith("unregister")) LISTENERS.remove(args[0]);
        else LISTENERS.add(args[0]);
        return null;
    }

    /** ActivityManager.getRunningTasks for the app: the activity in front first, then the app's own tasks. */
    public static List<ActivityManager.RunningTaskInfo> running(ActivityManager am, int max) {
        List<ActivityManager.RunningTaskInfo> own = am.getRunningTasks(max);
        ComponentName top = front;
        if (top == null) return own;
        List<ActivityManager.RunningTaskInfo> tasks = new ArrayList<>();
        if (own != null) {
            for (ActivityManager.RunningTaskInfo t : own) {
                if (t.topActivity != null && t.topActivity.equals(top)) return own;
            }
        }
        ActivityManager.RunningTaskInfo shown = new ActivityManager.RunningTaskInfo();
        shown.topActivity = top;
        shown.baseActivity = top;
        shown.taskId = -1;
        shown.numActivities = 1;
        tasks.add(shown);
        if (own != null) tasks.addAll(own);
        while (tasks.size() > Math.max(1, max)) tasks.remove(tasks.size() - 1);
        return tasks;
    }

    /** An accessibility event: a window of this package and class came up. Only activities count. */
    static void windowShown(Context c, CharSequence pkg, CharSequence cls) {
        if (pkg == null || cls == null) return;
        ComponentName shown = new ComponentName(pkg.toString(), cls.toString());
        if (shown.equals(front) || !activity(c, shown)) return;
        front = shown;
        for (Object listener : LISTENERS) {
            try {
                Method changed = listener.getClass().getMethod("onTaskStackChanged");
                changed.invoke(listener);
            } catch (ReflectiveOperationException | RuntimeException e) {
                OneHand.debug("task listener: " + e);
            }
        }
    }

    private static boolean activity(Context c, ComponentName name) {
        String key = name.flattenToShortString();
        Boolean known = ACTIVITIES.get(key);
        if (known != null) return known;
        boolean is;
        try {
            c.getPackageManager().getActivityInfo(name, PackageManager.MATCH_DISABLED_COMPONENTS);
            is = true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            is = false;
        }
        ACTIVITIES.put(key, is);
        return is;
    }
}
