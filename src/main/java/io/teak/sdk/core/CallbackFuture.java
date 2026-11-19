package io.teak.sdk.core;

import java.util.concurrent.FutureTask;

/**
 * A {@link java.util.concurrent.Future} that a callback completes. Waiting on it blocks only the
 * caller of <code>get()</code>; nothing is parked on its behalf while it is pending.
 */
public class CallbackFuture<T> extends FutureTask<T> {
    public CallbackFuture() {
        super(() -> null);
    }

    /**
     * Does nothing. Only {@link #complete(Object)} completes the future, so handing it to an
     * executor can't complete it with a null value.
     */
    @Override
    public void run() {
    }

    /**
     * Completes the future with the given value. Has no effect once it is complete or cancelled.
     */
    public void complete(T value) {
        set(value);
    }
}
