package org.ungoogled.samsunggallery.search;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PowerManager;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Search by content's background work, in Gallery's process: the one-time model download (on an unmetered network),
 * the whole library while the phone charges, and new photos (when MediaStore changes, and shortly after Gallery
 * opens) in small batches. Each run stops when Android stops the job, when the phone gets warm, or (away from the
 * charger) in battery saver, and goes on later.
 */
public final class IndexJob extends JobService {
    private static final String TAG = "UA";
    static final int DOWNLOAD = 0x5eac0001;
    static final int CHARGING = 0x5eac0002;
    static final int NEW = 0x5eac0003;
    static final int SOON = 0x5eac0004;
    /** Photos a run away from the charger looks at. */
    private static final int SMALL_BATCH = 30;

    /** One run at a time (the model takes about 200 MB while loaded). */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** Android's stop request, per job (one service runs all of them). */
    private final Map<Integer, AtomicBoolean> stops = new ConcurrentHashMap<>();

    /** Everything Search by content needs scheduled now (nothing before the user allowed it). */
    static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        ComponentName service = new ComponentName(context, IndexJob.class);
        try {
            if (!SearchModel.ready(context)) {
                if (scheduler.getPendingJob(DOWNLOAD) == null && !SearchByContent.gaveUp(context)) {
                    scheduler.schedule(new JobInfo.Builder(DOWNLOAD, service)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                            .setPersisted(true)
                            .build());
                }
                return;
            }
            if (scheduler.getPendingJob(SOON) == null) {
                scheduler.schedule(new JobInfo.Builder(SOON, service)
                        .setMinimumLatency(15_000)
                        .setRequiresBatteryNotLow(true)
                        .build());
            }
            if (scheduler.getPendingJob(NEW) == null) scheduleNew(context);
            if (scheduler.getPendingJob(CHARGING) == null) {
                scheduler.schedule(new JobInfo.Builder(CHARGING, service)
                        .setRequiresCharging(true)
                        .setRequiresBatteryNotLow(true)
                        .setPersisted(true)
                        .build());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "search: jobs not scheduled: " + e);
        }
    }

    private static void scheduleNew(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.schedule(new JobInfo.Builder(NEW, new ComponentName(context, IndexJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(10_000)
                .setTriggerContentMaxDelay(120_000)
                .setRequiresBatteryNotLow(true)
                .build());
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        for (int id : new int[]{DOWNLOAD, CHARGING, NEW, SOON}) scheduler.cancel(id);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!SearchByContent.allowed(this)) return false;
        AtomicBoolean stop = new AtomicBoolean();
        stops.put(params.getJobId(), stop);
        new Thread(() -> work(params, stop), "UA-search").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        AtomicBoolean stop = stops.get(params.getJobId());
        if (stop != null) stop.set(true);
        return true;
    }

    private void work(JobParameters params, AtomicBoolean stop) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        int id = params.getJobId();
        boolean again = false;
        boolean more = false;
        try {
            if (id == DOWNLOAD) {
                if (SearchModel.prepare(this, stop::get)) {
                    SearchByContent.downloadDone(this, true);
                    more = true;
                } else if (SearchModel.mismatched()) {
                    again = SearchByContent.downloadDone(this, false);
                } else {
                    // No network, stopped, or the download broke off: later, from where it got to.
                    again = true;
                }
            } else if (SearchModel.ready(this)) {
                boolean charging = id == CHARGING;
                if (RUNNING.compareAndSet(false, true)) {
                    try {
                        SearchIndex.Run run = SearchIndex.run(this, charging ? Integer.MAX_VALUE : SMALL_BATCH,
                                charging ? 0 : 2, () -> stop.get() || tooWarm(charging));
                        again = charging && run.left > 0;
                        // More than a small run takes: the charging run (one-off, gone once done) does the rest.
                        more = !charging && run.left > 0;
                    } finally {
                        RUNNING.set(false);
                    }
                } else {
                    // Another run is on it; the charging run comes back later for what that one leaves.
                    again = charging;
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "search: job " + Integer.toHexString(id) + " failed", e);
        } catch (LinkageError e) {
            Log.w(TAG, "search: the runtime did not load", e);
        }
        stops.remove(params.getJobId(), stop);
        if (!stop.get()) jobFinished(params, again);
        // Scheduling a job stops a running one with its id: only now. A content trigger fires once, so it is set again.
        if (id == NEW) scheduleNew(this);
        if (more) schedule(this);
    }

    /** Back off when the phone is warm, and away from the charger in battery saver. */
    private boolean tooWarm(boolean charging) {
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) return false;
        if (power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) {
            Log.i(TAG, "search: paused, the phone is warm");
            return true;
        }
        // Unplugged during the charging run: Android stops the job itself.
        return !charging && power.isPowerSaveMode();
    }
}
