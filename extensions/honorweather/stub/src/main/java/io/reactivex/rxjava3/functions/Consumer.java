package io.reactivex.rxjava3.functions;

/** Compile-only: RxJava 3's Consumer as Honor Weather bundles it. */
public interface Consumer<T> {
    void accept(T t) throws Throwable;
}
