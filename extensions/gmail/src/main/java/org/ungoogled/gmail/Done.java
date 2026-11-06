package org.ungoogled.gmail;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** A future that already holds its value, for answers Gmail would otherwise fetch. */
public final class Done<V> implements ListenableFuture<V> {
    private final V value;

    private Done(V value) {
        this.value = value;
    }

    /** Hide ads: the ad entries Gmail would place into the inbox, Promotions and Social -- none. */
    public static ListenableFuture<?> noAds() {
        return new Done<>(Collections.emptyList());
    }

    /** Remove telemetry: GrowthKit's start-up, reported as done without starting it -- no promo sync. */
    public static ListenableFuture<?> nothing() {
        return new Done<>(null);
    }

    @Override
    public void addListener(Runnable listener, Executor executor) {
        executor.execute(listener);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isDone() {
        return true;
    }

    @Override
    public V get() {
        return value;
    }

    @Override
    public V get(long timeout, TimeUnit unit) {
        return value;
    }
}
