package org.ungoogled.coloroslauncher;

import android.os.HandlerThread;
import android.os.Looper;

import com.oplus.dispatch.OCDMessageQueue;
import com.oplus.dispatch.SourceQueue;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ColorOS's work dispatcher (com.oplus.dispatch: queues, executors and handlers the launcher runs all its background
 * work on) is part of ColorOS's framework. Off ColorOS its stand-ins do the same with Android's own threads: a message
 * queue is a looper thread, a source queue runs its work in order on a thread of its own, an executor is a thread pool.
 */
public final class Dispatch {
    private static final Map<Looper, OCDMessageQueue> QUEUES = new WeakHashMap<>();
    private static final Map<OCDMessageQueue, Looper> LOOPERS = new WeakHashMap<>();
    private static final Map<SourceQueue, ExecutorService> SOURCES = new WeakHashMap<>();
    private static ExecutorService shared;

    private Dispatch() {
    }

    /** Threads named after the queue, like ColorOS's. */
    public static ThreadFactory threads(String name) {
        AtomicInteger n = new AtomicInteger();
        String base = name == null ? "ocd" : name;
        return r -> new Thread(r, base + "-" + n.incrementAndGet());
    }

    /** A started looper thread of that name. */
    public static Looper looper(String name) {
        HandlerThread thread = new HandlerThread(name == null ? "ocd-handler" : name);
        thread.start();
        return thread.getLooper();
    }

    /** The one message queue object of a looper (the launcher compares them to see whether it is on that thread). */
    public static synchronized OCDMessageQueue queue(Looper looper) {
        if (looper == null) return null;
        OCDMessageQueue queue = QUEUES.get(looper);
        if (queue == null) {
            queue = new OCDMessageQueue();
            QUEUES.put(looper, queue);
            LOOPERS.put(queue, looper);
        }
        return queue;
    }

    /** A message queue's looper; the main thread's for one this side did not make. */
    public static synchronized Looper looperOf(OCDMessageQueue queue) {
        Looper looper = queue == null ? null : LOOPERS.get(queue);
        return looper != null ? looper : Looper.getMainLooper();
    }

    /** Ends a message queue's thread. */
    public static synchronized void quit(OCDMessageQueue queue, boolean safely) {
        Looper looper = queue == null ? null : LOOPERS.get(queue);
        if (looper == null || looper == Looper.getMainLooper()) return;
        if (safely) looper.quitSafely(); else looper.quit();
    }

    /** Runs work asynchronously: in order on the source queue's own thread, else on a shared pool. */
    public static int async(SourceQueue queue, Runnable work) {
        if (work == null) return -1;
        ExecutorService executor;
        synchronized (Dispatch.class) {
            if (queue != null) {
                executor = SOURCES.get(queue);
                if (executor == null) {
                    executor = Executors.newSingleThreadExecutor(threads("ocd-source"));
                    SOURCES.put(queue, executor);
                }
            } else {
                if (shared == null) shared = Executors.newCachedThreadPool(threads("ocd-async"));
                executor = shared;
            }
        }
        executor.execute(work);
        return 0;
    }
}
