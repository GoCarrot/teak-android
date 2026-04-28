package io.teak.app.test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test-only scheduler that records every scheduled task in a FIFO queue. Tests advance the
 * world manually with {@link #runNext()} or {@link #runAll()}, eliminating any wall-clock
 * dependency on the production backoff curve.
 */
class DeterministicScheduler extends AbstractExecutorService implements ScheduledExecutorService {
    private final Queue<Runnable> pending = new LinkedList<>();
    private boolean shutdown = false;

    /** Runs the next pending task. Returns false if the queue was empty. */
    boolean runNext() {
        final Runnable next = pending.poll();
        if (next == null) return false;
        next.run();
        return true;
    }

    /** Runs every pending task in order, including any scheduled while running. */
    void runAll() {
        while (runNext()) {
        }
    }

    int pendingCount() {
        return pending.size();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if (!shutdown) {
            pending.offer(command);
        }
        return new NoopScheduledFuture<Void>();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (!shutdown) {
            pending.offer(() -> {
                try {
                    callable.call();
                } catch (Exception ignored) {
                }
            });
        }
        return new NoopScheduledFuture<V>();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return schedule(command, initialDelay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return schedule(command, initialDelay, unit);
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        final List<Runnable> remaining = new ArrayList<>(pending);
        pending.clear();
        return remaining;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown && pending.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public void execute(Runnable command) {
        if (!shutdown) command.run();
    }

    private static final class NoopScheduledFuture<V> implements ScheduledFuture<V> {
        private boolean cancelled = false;
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }
        @Override
        public int compareTo(Delayed o) {
            return 0;
        }
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }
        @Override
        public boolean isCancelled() {
            return cancelled;
        }
        @Override
        public boolean isDone() {
            return cancelled;
        }
        @Override
        public V get() {
            return null;
        }
        @Override
        public V get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
