package io.teak.app.test;

import android.content.Intent;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.Session;
import io.teak.sdk.event.LifecycleEvent;
import io.teak.sdk.event.RemoteConfigurationEvent;
import io.teak.sdk.event.SessionStateEvent;
import io.teak.sdk.event.UserIdEvent;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

// Resumed and RemoteConfigurationEvent are both dequeued before the Resumed handler has run, so the
// session the Resumed handler creates is not in the listener list taken for RemoteConfigurationEvent.
// It must still reach Configured, and go on to identify the user.
public class BackToBackResumedAndRemoteConfiguration extends TeakUnitTest {
    private static class GateEvent extends TeakEvent {
        GateEvent() {
            super("BackToBackResumedAndRemoteConfiguration.Gate");
        }
    }

    private CountDownLatch gateEntered;
    private CountDownLatch gateRelease;
    private CountDownLatch configured;
    private CountDownLatch identifying;

    @Before
    public void resetSessionAndListen() throws Exception {
        resetSessionStatics();

        final Method register = Session.class.getDeclaredMethod("registerStaticEventListeners");
        register.setAccessible(true);
        register.invoke(null);

        gateEntered = new CountDownLatch(1);
        gateRelease = new CountDownLatch(1);
        configured = new CountDownLatch(1);
        identifying = new CountDownLatch(1);

        // Holds the single event executor so every event posted after the gate is dequeued before any of their handlers run
        TeakEvent.addEventListener(new TeakEvent.EventListener() {
            @Override
            public void onNewEvent(@NonNull TeakEvent event) {
                if (!(event instanceof GateEvent)) return;
                gateEntered.countDown();
                try {
                    gateRelease.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });
        TeakEvent.addEventListener(new TeakEvent.EventListener() {
            @Override
            public void onNewEvent(@NonNull TeakEvent event) {
                if (!(event instanceof SessionStateEvent)) return;
                final Session.State state = ((SessionStateEvent) event).state;
                if (state == Session.State.Configured) {
                    configured.countDown();
                } else if (state == Session.State.IdentifyingUser) {
                    identifying.countDown();
                }
            }
        });
    }

    @Test
    public void sessionCreatedByResumedStillGetsRemoteConfiguration() throws Exception {
        TeakEvent.postEvent(new UserIdEvent("back_to_back", new Teak.UserConfiguration()));
        TeakEvent.postEvent(new GateEvent());
        assertTrue("Gate never ran", gateEntered.await(5, TimeUnit.SECONDS));

        TeakEvent.postEvent(new LifecycleEvent(LifecycleEvent.Resumed, mock(Intent.class), context));
        TeakEvent.postEvent(new RemoteConfigurationEvent(mockRemoteConfiguration()));
        awaitEventQueueDrained();

        gateRelease.countDown();

        assertTrue("Session never reached Configured", configured.await(5, TimeUnit.SECONDS));
        assertTrue("Session never reached IdentifyingUser", identifying.await(5, TimeUnit.SECONDS));
    }

    // The queue thread takes an event before it hands it to listeners, so an empty queue plus a short
    // settle means both events have been dequeued and handed to the executor.
    private static void awaitEventQueueDrained() throws Exception {
        final Field f = TeakEvent.class.getDeclaredField("eventQueue");
        f.setAccessible(true);
        final BlockingQueue<?> queue = (BlockingQueue<?>) f.get(null);
        final long deadline = System.currentTimeMillis() + 5000;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue("Event queue never drained", queue.isEmpty());
        Thread.sleep(200);
    }

    private static RemoteConfiguration mockRemoteConfiguration() {
        return new RemoteConfiguration(mock(AppConfiguration.class),
            "127.0.0.1",
            null,
            null,
            "mock_gcm_sender_id",
            "mock_firebase_app_id",
            false,
            false,
            null,
            null,
            600,
            new ArrayList<Teak.Channel.Category>(),
            true);
    }

    @After
    public void resetSessionStatics() throws Exception {
        if (gateRelease != null) gateRelease.countDown();
        setStaticField("currentSession", null);
        setStaticField("pendingUserId", null);
        setStaticField("pendingEmail", null);
        setStaticField("pendingFacebookId", null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        final Field f = Session.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
}
