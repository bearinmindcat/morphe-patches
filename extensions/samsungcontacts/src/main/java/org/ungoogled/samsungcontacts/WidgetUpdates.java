package org.ungoogled.samsungcontacts;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * When contacts change, Contacts refreshes its widgets by starting its widget update service. On One UI it is a
 * preloaded app that may do that from the background; elsewhere Android refuses to start a service for an app in the
 * background (BackgroundServiceStartNotAllowedException), and the exception crashed the app's background process.
 * The refresh then runs as a job of the same service (it is a JobService), which Android allows.
 */
public final class WidgetUpdates {
    private static final String TAG = "UA";
    /** Clear of the app's own job ids (around 100, 27356415) and of WorkManager's, which count up from 0. */
    private static final int JOB = 0x0C0A7AC7;

    private WidgetUpdates() {
    }

    public static ComponentName startService(Context context, Intent intent) {
        try {
            return context.startService(intent);
        } catch (IllegalStateException e) {
            ComponentName service = intent.getComponent();
            try {
                JobScheduler jobs = context.getSystemService(JobScheduler.class);
                if (jobs != null && service != null) {
                    JobInfo.Builder builder = new JobInfo.Builder(JOB, service);
                    JobInfo job;
                    try {
                        job = builder.build();
                    } catch (IllegalArgumentException noConstraints) {
                        // Android 13 wants a constraint or a deadline.
                        job = builder.setOverrideDeadline(0).build();
                    }
                    jobs.schedule(job);
                    Log.i(TAG, "widget update runs as a job (app in the background)");
                }
            } catch (RuntimeException ignored) {
                // The app's content-triggered job still refreshes the widgets.
            }
            return null;
        }
    }
}
