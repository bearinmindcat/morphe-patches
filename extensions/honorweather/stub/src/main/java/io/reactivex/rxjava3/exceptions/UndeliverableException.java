package io.reactivex.rxjava3.exceptions;

/** Compile-only: RxJava 3's wrapper of an error nobody was left to receive. */
public final class UndeliverableException extends IllegalStateException {
    public UndeliverableException(Throwable cause) {
        super(cause);
    }
}
