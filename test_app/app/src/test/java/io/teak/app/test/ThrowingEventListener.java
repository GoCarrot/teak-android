package io.teak.app.test;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.teak.sdk.TeakEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// A listener that throws must not stop the listeners after it from seeing the event, and the failure
// must still reach the uncaught exception handler.
public class ThrowingEventListener extends TeakUnitTest {
    private static class TestEvent extends TeakEvent {
        TestEvent() {
            super("ThrowingEventListener.Event");
        }
    }

    private final CopyOnWriteArrayList<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private final CountDownLatch uncaughtSeen = new CountDownLatch(1);
    private Thread.UncaughtExceptionHandler previousHandler;

    @Before
    public void captureUncaught() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            uncaught.add(throwable);
            uncaughtSeen.countDown();
        });
    }

    @After
    public void restoreUncaught() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }

    @Test
    public void listenersAfterAThrowingListenerStillRun() throws Exception {
        final CountDownLatch afterThrower = new CountDownLatch(1);
        TeakEvent.addEventListener(new TeakEvent.EventListener() {
            @Override
            public void onNewEvent(@NonNull TeakEvent event) {
                if (event instanceof TestEvent) throw new IllegalStateException("listener failed");
            }
        });
        TeakEvent.addEventListener(new TeakEvent.EventListener() {
            @Override
            public void onNewEvent(@NonNull TeakEvent event) {
                if (event instanceof TestEvent) afterThrower.countDown();
            }
        });

        TeakEvent.postEvent(new TestEvent());

        assertTrue("Listener after the thrower never ran", afterThrower.await(5, TimeUnit.SECONDS));
        assertTrue("Failure never reached the uncaught exception handler", uncaughtSeen.await(5, TimeUnit.SECONDS));
        assertEquals(1, uncaught.size());
        assertEquals("listener failed", uncaught.get(0).getMessage());
    }
}
