package io.teak.sdk.core;

import java.util.concurrent.FutureTask;

/**
 * A {@link java.util.concurrent.Future} that a callback completes. Waiting on it blocks only the
 * caller of <code>get()</code>; nothing is parked on its behalf while it is pending.
 */
public class CallbackFuture<T> extends FutureTask<T> {
    public CallbackFuture() {
        // The task is never run; complete() supplies the value
        super(() -> null);
    }

    /**
     * Completes the future with the given value. Has no effect once it is complete or cancelled.
     */
    public void complete(T value) {
        set(value);
    }
}
