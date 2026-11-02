package io.reactivex.rxjava3.core;

import java.util.concurrent.Callable;

/** Compile-only: RxJava 3's Observable as Honor Weather bundles it (Retrofit's service methods return one). */
public abstract class Observable<T> {
    public static <T> Observable<T> fromCallable(Callable<? extends T> callable) {
        throw new UnsupportedOperationException("stub");
    }

    public static <T> Observable<T> error(Throwable error) {
        throw new UnsupportedOperationException("stub");
    }
}
