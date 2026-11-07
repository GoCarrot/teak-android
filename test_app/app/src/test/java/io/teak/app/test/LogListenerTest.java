package io.teak.app.test;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import io.teak.sdk.Log;
import io.teak.sdk.Teak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// A host LogListener runs arbitrary code, so Log must never call it while holding its own lock.
// Each test uses a fresh Log; the pre-configuration window (events queued until setSdkRaven)
// can't be reproduced on the singleton, which is already past it.
@RunWith(MockitoJUnitRunner.class)
public class LogListenerTest extends TeakUnitTest {
    // Constructing a Log under TeakUnitTest also queues the SDK's own config events; only the
    // events these tests log are of interest.
    private static final List<String> TEST_EVENTS = Arrays.asList("queued", "reentrant", "first", "second", "third", "outer", "inner");
    private final List<String> received = new CopyOnWriteArrayList<>();

    @After
    public void tearDown() {
        Teak.log.setSdkRaven(null);
    }

    private abstract class Listener extends Teak.LogListener {
        @Override
        public void logEvent(String logEvent, String logLevel, Map<String, Object> logData) {
            if (TEST_EVENTS.contains(logEvent)) {
                received.add(logEvent);
            }
            onEvent(logEvent);
        }

        abstract void onEvent(String logEvent);
    }

    // A listener that logs from inside its callback while the queue is draining used to add to
    // queuedLogEvents mid-iteration (ConcurrentModificationException).
    @Test
    public void listenerMayLogWhileQueuedEventsDrain() {
        final Log log = new Log("Teak.Test", 0);
        final AtomicBoolean reentered = new AtomicBoolean(false);
        log.setLogListener(new Listener() {
            @Override
            void onEvent(String logEvent) {
                if ("queued".equals(logEvent) && reentered.compareAndSet(false, true)) {
                    log.i("reentrant", "logged from inside the listener");
                }
            }
        });

        log.i("queued", "fired before the raven existed");
        log.setSdkRaven(null);

        assertEquals(Arrays.asList("queued", "reentrant"), received);
    }

    // A listener that blocks on another thread that logs deadlocked while Log held its lock
    // across the callback.
    @Test
    public void listenerRunsWithoutHoldingLogLock() throws Exception {
        final Log log = new Log("Teak.Test", 0);
        log.markConfigurationReady();

        final AtomicBoolean otherThreadFinished = new AtomicBoolean(false);
        log.setLogListener(new Listener() {
            @Override
            void onEvent(String logEvent) {
                if (!"outer".equals(logEvent)) return;
                final Thread other = new Thread(() -> {
                    log.i("inner", "logged from another thread");
                    otherThreadFinished.set(true);
                });
                other.start();
                try {
                    other.join(2000);
                } catch (InterruptedException ignored) {
                }
            }
        });

        log.i("outer", "starts the callback");

        assertTrue("another thread must be able to log while a listener callback is running", otherThreadFinished.get());
    }

    // Same as above, during the pre-configuration drain.
    @Test
    public void drainDoesNotHoldLogLockDuringCallbacks() {
        final Log log = new Log("Teak.Test", 0);
        final AtomicBoolean otherThreadFinished = new AtomicBoolean(false);
        log.setLogListener(new Listener() {
            @Override
            void onEvent(String logEvent) {
                if (!"queued".equals(logEvent)) return;
                final Thread other = new Thread(() -> {
                    log.i("inner", "logged from another thread");
                    otherThreadFinished.set(true);
                });
                other.start();
                try {
                    other.join(2000);
                } catch (InterruptedException ignored) {
                }
            }
        });

        log.i("queued", "fired before the raven existed");
        log.setSdkRaven(null);

        assertTrue("another thread must be able to log while a drain callback is running", otherThreadFinished.get());
        assertEquals(Arrays.asList("queued", "inner"), received);
    }

    // A throwing host listener must not strand the queue or leave Log stuck in the draining state.
    @Test
    public void throwingListenerDuringDrainDoesNotWedgeLog() {
        final Log log = new Log("Teak.Test", 0);
        log.setLogListener(new Listener() {
            @Override
            void onEvent(String logEvent) {
                if ("first".equals(logEvent)) {
                    throw new RuntimeException("listener boom");
                }
            }
        });

        log.i("first", "queued, listener throws");
        log.i("second", "queued behind it");
        log.setSdkRaven(null);

        assertEquals("events queued behind the thrower must still replay", Arrays.asList("first", "second"), received);

        log.i("third", "logged after the drain");
        assertTrue("log() must reach the listener once the drain is done", received.contains("third"));
    }
}
