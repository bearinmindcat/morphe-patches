package org.ungoogled.onepluslauncher;

import android.app.TaskInfo;
import android.content.ComponentName;

/**
 * Running tasks: Android shows an installed home app none of the other apps' tasks, so the launcher's "running task"
 * lookups find none (null). Reading the top activity of none is "no top activity" here instead of a
 * NullPointerException (a wallpaper change stopped the launcher).
 */
public final class Tasks {
    private Tasks() {
    }

    public static ComponentName topActivity(TaskInfo task) {
        return task != null ? task.topActivity : null;
    }
}
