package org.ungoogled.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Looper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class EnrichmentServiceTest extends LibraryTestSupport {
    @Before public void resetService() throws Exception {
        awaitControls();
        awaitQueue();
        set(EnrichmentService.class, "running", false);
        set(EnrichmentService.class, "starting", false);
        set(PlaceEnrichment.class, "pauseRequested", false);
        SavedStore.enrichmentPaused = false;
        SavedStore.enrichmentPauseReason = SavedStore.enrichmentLastFailure = "";
        QueueRecoveryTest.online(context, false);
    }

    @Test public void foregroundEntryControlsAndTimeoutDoNotWaitForImportStoreLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread parser = new Thread(() -> {
            synchronized (SavedStore.class) {
                locked.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        parser.start();
        assertTrue(locked.await(1, TimeUnit.SECONDS));
        ServiceController<EnrichmentService> controller = Robolectric.buildService(EnrichmentService.class);
        try {
            long start = System.nanoTime();
            controller.create();
            Notification notification = Shadows.shadowOf(controller.get()).getLastForegroundNotification();
            assertNotNull(notification);
            assertEquals(2, notification.actions.length);
            assertTrue("Foreground entry must not wait behind import parsing", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            Intent pause = Shadows.shadowOf(notification.actions[0].actionIntent).getSavedIntent();
            controller.get().onStartCommand(pause, 0, 1);
            assertTrue(PlaceEnrichment.progress().startsWith("Paused by you"));
            Intent cancel = Shadows.shadowOf(notification.actions[1].actionIntent).getSavedIntent();
            controller.get().onStartCommand(cancel, 0, 2);
            assertTrue(PlaceEnrichment.progress().startsWith("Queue cancelled"));
            assertFalse("Normal stop must wait for its checkpoint", PlaceEnrichment.shouldStopService());
            assertTrue("Notification controls must not block parsing", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            controller.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            assertTrue(Shadows.shadowOf(controller.get()).isStoppedBySelf());
            assertTrue("Android timeout must stop immediately", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
            assertEquals(1, release.getCount());
        } finally {
            release.countDown();
            parser.join(1000);
            awaitControls();
            awaitQueue();
            controller.destroy();
        }
        reload();
        assertTrue(SavedStore.enrichmentPaused);
        assertTrue(SavedStore.enrichmentPauseReason.contains("time limit"));
    }

    @Test public void foregroundRequestIsCoalescedAndNeverStartsAfterBackgrounding() throws Exception {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).create().start().resume();
        StartRecorder calls = new StartRecorder(context);
        try {
            PlaceEnrichment.foreground(activity.get());
            EnrichmentService.request(calls);
            PlaceEnrichment.background(activity.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, calls.count);
            PlaceEnrichment.foreground(activity.get());
            EnrichmentService.request(calls);
            EnrichmentService.request(calls);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, calls.count);
        } finally {
            PlaceEnrichment.background(activity.get());
            activity.pause().stop().destroy();
        }
    }

    @Test public void startRestrictionIsReportedWithoutLosingEntriesOrLatchingStart() throws Exception {
        SavedStore.Place p = SavedStore.fromMapsLink("https://maps.google.com/?cid=42", "Retained");
        p.detailsState = "pending";
        p.attempts = 2;
        SavedStore.places.put(p.key(), p);
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).create().start().resume();
        StartRecorder calls = new StartRecorder(context);
        calls.failure = new IllegalStateException("Start restricted");
        try {
            PlaceEnrichment.foreground(activity.get());
            EnrichmentService.request(calls);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            awaitQueue();
            assertEquals(1, calls.count);
            assertFalse(SavedStore.enrichmentLastFailure.isEmpty());
            assertEquals("pending", p.detailsState);
            assertEquals(2, p.attempts);
            assertEquals(1, SavedStore.places.size());
            calls.failure = null;
            EnrichmentService.request(calls);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, calls.count);
        } finally {
            PlaceEnrichment.background(activity.get());
            activity.pause().stop().destroy();
        }
    }

    @Test public void missingManifestServiceDoesNotLeaveStartPermanentlyLatched() throws Exception {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).create().start().resume();
        StartRecorder calls = new StartRecorder(context);
        calls.missing = true;
        try {
            PlaceEnrichment.foreground(activity.get());
            EnrichmentService.request(calls);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            awaitQueue();
            assertFalse(SavedStore.enrichmentLastFailure.isEmpty());
            calls.missing = false;
            EnrichmentService.request(calls);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(2, calls.count);
        } finally {
            PlaceEnrichment.background(activity.get());
            activity.pause().stop().destroy();
        }
    }

    @Test @Config(sdk = 35)
    public void deniedNotificationPermissionStillStartsForegroundAndTimeoutStopsIt() throws Exception {
        Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        ServiceController<EnrichmentService> service = Robolectric.buildService(EnrichmentService.class).create();
        try {
            assertTrue(EnrichmentService.running());
            assertNotNull(Shadows.shadowOf(service.get()).getLastForegroundNotification());
            service.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            assertTrue(Shadows.shadowOf(service.get()).isStoppedBySelf());
            awaitControls();
        } finally { service.destroy(); }
        assertFalse(EnrichmentService.running());
        reload();
        assertTrue(SavedStore.enrichmentPaused);
    }

    private static final class StartRecorder extends ContextWrapper {
        int count;
        boolean missing;
        RuntimeException failure;

        StartRecorder(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public ComponentName startForegroundService(Intent intent) {
            count++;
            if (failure != null) throw failure;
            return missing ? null : new ComponentName(this, EnrichmentService.class);
        }
    }
}
