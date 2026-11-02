package io.reactivex.rxjava3.plugins;

import io.reactivex.rxjava3.functions.Consumer;

/** Compile-only: RxJava 3's global hooks as Honor Weather bundles them. */
public final class RxJavaPlugins {
    private RxJavaPlugins() {
    }

    public static Consumer<? super Throwable> getErrorHandler() {
        throw new UnsupportedOperationException("stub");
    }

    public static void setErrorHandler(Consumer<? super Throwable> handler) {
        throw new UnsupportedOperationException("stub");
    }
}
