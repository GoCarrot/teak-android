package io.teak.app.test;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.teak.sdk.Log;
import io.teak.sdk.Teak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    // Logs "inner" from another thread and waits (bounded) for it. Returns whether it finished.
    private static boolean logFromAnotherThread(final Log log) {
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Thread other = new Thread(() -> {
            log.i("inner", "logged from another thread");
            finished.set(true);
        });
        other.start();
        try {
            other.join(2000);
        } catch (InterruptedException ignored) {
        }
        return finished.get();
    }

    // A listener may log from inside its callback while queued events replay.
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

    // Another thread can log while a listener callback is running.
    @Test
    public void otherThreadCanLogDuringListenerCallback() {
        final Log log = new Log("Teak.Test", 0);
        log.markConfigurationReady();

        final AtomicBoolean otherThreadFinished = new AtomicBoolean(false);
        log.setLogListener(new Listener() {
            @Override
            void onEvent(String logEvent) {
                if ("outer".equals(logEvent)) {
                    otherThreadFinished.set(logFromAnotherThread(log));
                }
            }
        });

        log.i("outer", "starts the callback");

        assertTrue("another thread must be able to log while a listener callback is running", otherThreadFinished.get());
    }

    // Same, while queued events replay.
    @Test
    public void otherThreadCanLogDuringReplayCallback() {
        final Log log = new Log("Teak.Test", 0);
        final AtomicBoolean otherThreadFinished = new AtomicBoolean(false);
        log.setLogListener(new Listener() {
            @Override
            void onEvent(String logEvent) {
                if ("queued".equals(logEvent)) {
                    otherThreadFinished.set(logFromAnotherThread(log));
                }
            }
        });

        log.i("queued", "fired before the raven existed");
        log.setSdkRaven(null);

        assertTrue("another thread must be able to log while a replay callback is running", otherThreadFinished.get());
        assertEquals(Arrays.asList("queued", "inner"), received);
    }

    // A throwing listener doesn't strand the rest of the queue or stop later events reaching it.
    @Test
    public void throwingListenerDuringReplayDoesNotStopLogging() {
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

        log.i("third", "logged after the replay");
        assertTrue("log() must reach the listener once the replay is done", received.contains("third"));
    }

    private static void holdMonitor(final Object monitor, final CountDownLatch holding, final CountDownLatch release) {
        synchronized (monitor) {
            holding.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // Building a log payload reads the common payload, which configuration writes from another
    // thread, so the read must hold the common payload's lock.
    @Test
    public void payloadCopyWaitsForCommonPayloadLock() throws Exception {
        final Log log = new Log("Teak.Test", 0);
        log.markConfigurationReady();

        final Field field = Log.class.getDeclaredField("commonPayload");
        field.setAccessible(true);
        final Object commonPayload = field.get(log);

        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread holder = new Thread(() -> holdMonitor(commonPayload, holding, release));
        holder.start();
        assertTrue(holding.await(2, TimeUnit.SECONDS));

        final AtomicBoolean logged = new AtomicBoolean(false);
        final Thread logger = new Thread(() -> {
            log.i("inner", "logged while commonPayload is locked");
            logged.set(true);
        });
        logger.start();
        logger.join(300);
        final boolean loggedWhileLocked = logged.get();

        release.countDown();
        holder.join(2000);
        logger.join(2000);

        assertFalse("log payload must not be built while another thread holds the common payload lock", loggedWhileLocked);
        assertTrue("log must complete once the lock is released", logged.get());
    }
}
