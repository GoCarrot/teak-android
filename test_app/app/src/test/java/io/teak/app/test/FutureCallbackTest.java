package io.teak.app.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.teak.sdk.Request;
import io.teak.sdk.Teak;
import io.teak.sdk.TeakInstance;
import io.teak.sdk.TeakNotification;
import io.teak.sdk.configuration.AppConfiguration;
import io.teak.sdk.configuration.RemoteConfiguration;
import io.teak.sdk.core.CallbackFuture;
import io.teak.sdk.core.Session;
import io.teak.sdk.json.JSONObject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * The futures returned by setChannelState, setCategoryState, Teak.Notification.schedule and
 * rewardFromRewardId complete from the request callback and hold no thread while they wait.
 */
public class FutureCallbackTest extends TeakHttpUnitTest {
    private static final int CALLS = 25;

    private RemoteConfiguration originalRemoteConfiguration;
    private TeakInstance originalInstance;
    private Object originalCurrentSession;
    private Object originalSessionState;

    @Before
    public void setUpInstanceAndEndpoints() throws Exception {
        Request.setTeakApiKey("test_teak_api_key");
        originalRemoteConfiguration = (RemoteConfiguration) field(Request.class, "remoteConfiguration").get(null);

        originalInstance = Teak.Instance;
        // The calls under test read no instance state beyond the enabled flag
        final TeakInstance instance = mock(TeakInstance.class, CALLS_REAL_METHODS);
        field(TeakInstance.class, "stateMutex").set(instance, new Object());
        Teak.Instance = instance;

        originalCurrentSession = field(Session.class, "currentSession").get(null);
        originalSessionState = field(Session.class, "state").get(Session.NullSession);
        field(Session.class, "currentSession").set(null, null);
        drainQueue();
    }

    @After
    public void restore() throws Exception {
        drainQueue();
        field(Session.class, "currentSession").set(null, originalCurrentSession);
        field(Session.class, "state").set(Session.NullSession, originalSessionState);
        Teak.Instance = originalInstance;
        setRemoteConfiguration(originalRemoteConfiguration);
    }

    private static Field field(Class<?> cls, String name) throws Exception {
        final Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void setRemoteConfiguration(RemoteConfiguration remoteConfiguration) throws Exception {
        field(Request.class, "remoteConfiguration").set(null, remoteConfiguration);
    }

    @SuppressWarnings("unchecked")
    private static void drainQueue() throws Exception {
        ((List<Object>) field(Session.class, "userIdReadyRunnableQueue").get(null)).clear();
    }

    private void blackholeEndpoints() throws Exception {
        final JSONObject endpoints = new JSONObject("{"
                                                    + "\"127.0.0.1\":{"
                                                    + "\"/me/channel_state.json\":{\"blackhole\":true},"
                                                    + "\"/me/category_state.json\":{\"blackhole\":true},"
                                                    + "\"/me/local_notify.json\":{\"blackhole\":true}},"
                                                    + "\"rewards.gocarrot.com\":{\"/reward_id/clicks\":{\"blackhole\":true}}"
                                                    + "}");
        setRemoteConfiguration(new RemoteConfiguration(mock(AppConfiguration.class), "127.0.0.1", null, null,
            "mock_gcm_sender_id", "mock_firebase_app_id", false, false, endpoints, null,
            600, new ArrayList<Teak.Channel.Category>(), true));
    }

    // Puts the session in the state where whenUserIdIsReadyRun runs its work right away
    private static void identifySession() throws Exception {
        field(Session.class, "state").set(Session.NullSession, Session.State.UserIdentified);
        field(Session.class, "currentSession").set(null, Session.NullSession);
    }

    private static Set<Thread> liveThreads() {
        return new HashSet<>(Thread.getAllStackTraces().keySet());
    }

    private static List<Future<?>> makeCalls(int count) {
        final List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(Teak.setChannelState(Teak.Channel.Type.MobilePush, Teak.Channel.State.OptOut));
            futures.add(Teak.setCategoryState(Teak.Channel.Type.MobilePush, "category", Teak.Channel.State.OptOut));
            futures.add(Teak.Notification.schedule("creative_id", 60, new java.util.HashMap<String, Object>()));
            futures.add(TeakNotification.Reward.rewardFromRewardId("reward_id"));
        }
        return futures;
    }

    @Test
    public void callsBeforeIdentifyHoldNoThread() throws Exception {
        // Warm up anything lazily started so the snapshot only sees threads the calls create
        makeCalls(1);
        final Set<Thread> before = liveThreads();

        final List<Future<?>> futures = makeCalls(CALLS);

        final List<String> started = new ArrayList<>();
        for (Thread t : liveThreads()) {
            if (!before.contains(t) && t.isAlive()) started.add(t.getName());
        }
        assertEquals(4 * CALLS, futures.size());
        for (Future<?> f : futures) {
            assertNotNull(f);
            assertFalse(f.isDone());
        }
        assertTrue("calls started threads: " + started, started.isEmpty());
    }

    @Test
    public void cancelledFutureReportsCancelled() throws Exception {
        final List<Future<?>> futures = makeCalls(1);
        for (Future<?> f : futures) {
            assertTrue(f.cancel(true));
            assertTrue(f.isCancelled());
            assertTrue(f.isDone());
            try {
                f.get(1, TimeUnit.SECONDS);
                fail("get() on a cancelled future must throw");
            } catch (CancellationException expected) {
            }
        }
    }

    @Test
    public void runningTheFutureDoesNotCompleteIt() throws Exception {
        final CallbackFuture<String> future = new CallbackFuture<>();
        future.run();
        assertFalse(future.isDone());

        future.complete("value");
        assertEquals("value", future.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void channelStateThatFailsBeforeSendingCompletesWithError() throws Exception {
        blackholeEndpoints();
        identifySession();

        final Teak.Channel.Reply reply = Teak.setChannelState(Teak.Channel.Type.MobilePush, Teak.Channel.State.OptOut).get(5, TimeUnit.SECONDS);
        assertTrue(reply.error);
    }

    @Test
    public void categoryStateThatFailsBeforeSendingCompletesWithError() throws Exception {
        blackholeEndpoints();
        identifySession();

        final Teak.Channel.Reply reply = Teak.setCategoryState(Teak.Channel.Type.MobilePush, "category", Teak.Channel.State.OptOut).get(5, TimeUnit.SECONDS);
        assertTrue(reply.error);
    }

    @Test
    public void scheduleThatFailsBeforeSendingCompletesWithError() throws Exception {
        blackholeEndpoints();
        identifySession();

        final Teak.Notification.Reply reply = Teak.Notification.schedule("creative_id", 60, new java.util.HashMap<String, Object>()).get(5, TimeUnit.SECONDS);
        assertTrue(reply.error);
    }

    @Test
    public void rewardClaimThatFailsBeforeSendingCompletesWithUnknownReward() throws Exception {
        blackholeEndpoints();
        identifySession();

        final TeakNotification.Reward reward = TeakNotification.Reward.rewardFromRewardId("reward_id").get(5, TimeUnit.SECONDS);
        assertEquals(TeakNotification.Reward.UNKNOWN, reward.status);
    }

    @Test
    public void channelStateReplyReachesTheFuture() throws Exception {
        stubFor(post(urlEqualTo("/me/channel_state.json")).willReturn(aResponse().withStatus(200)
                                                                          .withBody("{\"status\":\"ok\",\"state\":\"opt_out\",\"channel\":\"push\"}")));
        identifySession();

        final Teak.Channel.Reply reply = Teak.setChannelState(Teak.Channel.Type.MobilePush, Teak.Channel.State.OptOut).get(5, TimeUnit.SECONDS);
        assertFalse(reply.error);
        assertEquals(Teak.Channel.State.OptOut, reply.state);
    }
}
