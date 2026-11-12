package io.teak.app.test;

import android.content.Intent;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.Session;
import io.teak.sdk.event.LifecycleEvent;
import io.teak.sdk.event.LogoutEvent;
import io.teak.sdk.event.RemoteConfigurationEvent;
import io.teak.sdk.event.SessionStateEvent;
import io.teak.sdk.event.UserIdEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// identifyUser then logout, both before any session exists. Logout must not throw, and must discard the
// pending user so the first session doesn't identify a player the game logged out.
public class LogoutBeforeSession extends TeakUnitTest {
    private final CopyOnWriteArrayList<Throwable> uncaught = new CopyOnWriteArrayList<>();
    private Thread.UncaughtExceptionHandler previousHandler;
    private CountDownLatch created;
    private CountDownLatch configured;
    private CountDownLatch identifying;
    private CountDownLatch loggedOut;

    @Before
    public void resetSessionAndListen() throws Exception {
        resetSessionStatics();

        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> uncaught.add(throwable));

        final Method register = Session.class.getDeclaredMethod("registerStaticEventListeners");
        register.setAccessible(true);
        register.invoke(null);

        created = new CountDownLatch(1);
        configured = new CountDownLatch(1);
        identifying = new CountDownLatch(1);
        loggedOut = new CountDownLatch(1);

        // Registered after Session's listener and run in order on a single executor, so this firing means Session's handler has run
        TeakEvent.addEventListener(new TeakEvent.EventListener() {
            @Override
            public void onNewEvent(@NonNull TeakEvent event) {
                if (event instanceof LogoutEvent) loggedOut.countDown();
            }
        });
        TeakEvent.addEventListener(new TeakEvent.EventListener() {
            @Override
            public void onNewEvent(@NonNull TeakEvent event) {
                if (!(event instanceof SessionStateEvent)) return;
                final Session.State state = ((SessionStateEvent) event).state;
                if (state == Session.State.Created) {
                    created.countDown();
                } else if (state == Session.State.Configured) {
                    configured.countDown();
                } else if (state == Session.State.IdentifyingUser) {
                    identifying.countDown();
                }
            }
        });
    }

    @Test
    public void unattributedLaunch() throws Exception {
        assertLogoutDiscardsPendingUser(mock(Intent.class));
    }

    @Test
    public void attributedLaunch() throws Exception {
        final Intent intent = mock(Intent.class);
        when(intent.getBooleanExtra("teakIsFirstLaunch", false)).thenReturn(true);
        assertLogoutDiscardsPendingUser(intent);
    }

    private void assertLogoutDiscardsPendingUser(Intent launchIntent) throws Exception {
        TeakEvent.postEvent(new UserIdEvent("logout_before_session", new Teak.UserConfiguration()));
        TeakEvent.postEvent(new LogoutEvent());

        assertTrue("Logout was never processed", loggedOut.await(5, TimeUnit.SECONDS));

        assertEquals("Logout before a session threw: " + uncaught, 0, uncaught.size());
        assertNull("pendingUserId survived logout", getStaticField("pendingUserId"));
        assertNull("pendingEmail survived logout", getStaticField("pendingEmail"));
        assertNull("pendingFacebookId survived logout", getStaticField("pendingFacebookId"));

        TeakEvent.postEvent(new LifecycleEvent(LifecycleEvent.Resumed, launchIntent, context));
        assertTrue("Session was never created", created.await(5, TimeUnit.SECONDS));
        TeakEvent.postEvent(new RemoteConfigurationEvent(mockRemoteConfiguration()));

        assertTrue("Session never reached Configured", configured.await(5, TimeUnit.SECONDS));
        assertTrue("Session identified a logged out user", !identifying.await(1, TimeUnit.SECONDS));
        assertNull("Session carries a user id", getSessionUserId());
    }

    private static String getSessionUserId() throws Exception {
        final Method getCurrent = Session.class.getDeclaredMethod("getCurrentSessionOrNull");
        getCurrent.setAccessible(true);
        final Session session = (Session) getCurrent.invoke(null);
        final Field userId = Session.class.getDeclaredField("userId");
        userId.setAccessible(true);
        return (String) userId.get(session);
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
        if (previousHandler != null) Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        setStaticField("currentSession", null);
        setStaticField("pendingUserId", null);
        setStaticField("pendingEmail", null);
        setStaticField("pendingFacebookId", null);
    }

    private static Object getStaticField(String name) throws Exception {
        final Field f = Session.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setStaticField(String name, Object value) throws Exception {
        final Field f = Session.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
}
