package io.teak.app.test;

import android.content.Intent;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
import static org.mockito.Mockito.when;

// identifyUser is called before any session exists, then the first session is created by
// onActivityResumed, then remote configuration arrives. The session must go on to IdentifyingUser.
public class IdentifyBeforeSession extends TeakUnitTest {
    private CountDownLatch created;
    private CountDownLatch identifying;

    @Before
    public void resetSessionAndListen() throws Exception {
        resetSessionStatics();

        final Method register = Session.class.getDeclaredMethod("registerStaticEventListeners");
        register.setAccessible(true);
        register.invoke(null);

        created = new CountDownLatch(1);
        identifying = new CountDownLatch(1);
        TeakEvent.addEventListener(new TeakEvent.EventListener() {
            @Override
            public void onNewEvent(@NonNull TeakEvent event) {
                if (!(event instanceof SessionStateEvent)) return;
                final Session.State state = ((SessionStateEvent) event).state;
                if (state == Session.State.Created) {
                    created.countDown();
                } else if (state == Session.State.IdentifyingUser) {
                    identifying.countDown();
                }
            }
        });
    }

    @Test
    public void firstLaunchInstallReferrer() throws Exception {
        final Intent intent = mock(Intent.class);
        when(intent.getBooleanExtra("teakIsFirstLaunch", false)).thenReturn(true);
        assertIdentifiesAfter(intent);
    }

    @Test
    public void unattributedLaunch() throws Exception {
        assertIdentifiesAfter(mock(Intent.class));
    }

    private void assertIdentifiesAfter(Intent launchIntent) throws Exception {
        TeakEvent.postEvent(new UserIdEvent("identify_before_session", new Teak.UserConfiguration()));
        TeakEvent.postEvent(new LifecycleEvent(LifecycleEvent.Resumed, launchIntent, context));

        // Remote configuration arrives after a settings.json round trip, so the session exists by then
        assertTrue("Session was never created", created.await(5, TimeUnit.SECONDS));
        TeakEvent.postEvent(new RemoteConfigurationEvent(mockRemoteConfiguration()));

        assertTrue("Session never reached IdentifyingUser", identifying.await(5, TimeUnit.SECONDS));
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
