package org.ungoogled.samsunggallery;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Whether an activity is in front. One UI answers it (Activity.semIsResumed); Gallery's table for other phones answers
 * "never", so everything Gallery only starts from a resumed screen -- Edit first -- did nothing ("Edit Menu Select
 * failed: paused"). Answered from Activity's own isResumed(), or where that is refused, from the lifecycle Gallery's
 * activities report.
 */
public final class ActivityState {
    private static final Set<Activity> RESUMED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile boolean tracking;
    private static volatile Method isResumed;
    private static volatile boolean refused;

    private ActivityState() {
    }

    /** Starts following the app's activities; called once, when the app's process starts. */
    static void track(Context context) {
        if (tracking || !(context instanceof Application)) return;
        tracking = true;
        ((Application) context).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreResumed(Activity activity) {
                RESUMED.add(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                RESUMED.remove(activity);
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                RESUMED.remove(activity);
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle state) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {
            }
        });
    }

    public static boolean isResumed(Activity activity) {
        if (activity == null) return false;
        if (!refused) {
            try {
                Method m = isResumed;
                if (m == null) {
                    m = Activity.class.getDeclaredMethod("isResumed");
                    m.setAccessible(true);
                    isResumed = m;
                }
                return (Boolean) m.invoke(activity);
            } catch (ReflectiveOperationException | RuntimeException e) {
                refused = true;
            }
        }
        return tracking ? RESUMED.contains(activity) : activity.hasWindowFocus();
    }
}
