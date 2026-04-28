package io.teak.app.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.teak.sdk.Teak;
import io.teak.sdk.core.RewardClaimManager;
import io.teak.sdk.core.Session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

// .Silent: makeFakeLaunchData stubs every Uri queryParameter the
// AttributedLaunchData constructor reads, but tests that don't consume launchData
// would otherwise trip strict-mode "unnecessary stubbings".
@RunWith(MockitoJUnitRunner.Silent.class)
public class RewardClaimManagerTest extends TeakUnitTest {

    private RecordingSender sender;
    private DeterministicScheduler scheduler;

    @Before
    public void resetManager() {
        RewardClaimManager.get().resetForTest();
        sender = new RecordingSender();
        scheduler = new DeterministicScheduler();
        RewardClaimManager.get().setSenderForTest(sender);
        RewardClaimManager.get().setSchedulerForTest(scheduler);
    }

    @After
    public void clearManager() {
        RewardClaimManager.get().resetForTest();
        RewardClaimManager.get().setSenderForTest(null);
    }

    // --- backoff curve ---

    @Test
    public void computeBackoffMs_doublesUntilCeiling() {
        assertEquals(2000L, RewardClaimManager.computeBackoffMs(0, 2000, 30000));
        assertEquals(4000L, RewardClaimManager.computeBackoffMs(1, 2000, 30000));
        assertEquals(8000L, RewardClaimManager.computeBackoffMs(2, 2000, 30000));
        assertEquals(16000L, RewardClaimManager.computeBackoffMs(3, 2000, 30000));
        assertEquals(30000L, RewardClaimManager.computeBackoffMs(4, 2000, 30000));
        assertEquals(30000L, RewardClaimManager.computeBackoffMs(5, 2000, 30000));
        assertEquals(30000L, RewardClaimManager.computeBackoffMs(20, 2000, 30000));
    }

    @Test
    public void computeBackoffMs_negativeAttemptTreatedAsZero() {
        assertEquals(2000L, RewardClaimManager.computeBackoffMs(-1, 2000, 30000));
        assertEquals(2000L, RewardClaimManager.computeBackoffMs(-100, 2000, 30000));
    }

    @Test
    public void computeBackoffMs_capsAtCeilingForOverflowSafetyBoundary() {
        // 31-shift would overflow int width of 2000; manager clamps before that.
        assertEquals(30000L, RewardClaimManager.computeBackoffMs(31, 2000, 30000));
        assertEquals(30000L, RewardClaimManager.computeBackoffMs(1_000_000, 2000, 30000));
    }

    // --- dedupe ---

    @Test
    public void startPoll_dedupesReentrantStartForSameEventId() throws Exception {
        final Session session = makeSyntheticSession();
        final Teak.AttributedLaunchData launchData = null;

        RewardClaimManager.get().startPoll("evt-1", "reward-1", session, launchData);
        RewardClaimManager.get().startPoll("evt-1", "reward-1", session, launchData);
        RewardClaimManager.get().startPoll("evt-1", "reward-1", session, launchData);

        assertEquals(1, RewardClaimManager.get().inFlightCount());
    }

    @Test
    public void startPoll_distinctEventIdsCoexist() throws Exception {
        final Session session = makeSyntheticSession();
        final Teak.AttributedLaunchData launchData = null;

        RewardClaimManager.get().startPoll("evt-a", "reward-1", session, launchData);
        RewardClaimManager.get().startPoll("evt-b", "reward-1", session, launchData);

        assertEquals(2, RewardClaimManager.get().inFlightCount());
    }

    // --- session lifecycle ---

    @Test
    public void cancelClaimsForSession_dropsClaimsTiedToThatSession() throws Exception {
        final Session expiringSession = makeSyntheticSession();
        final Teak.AttributedLaunchData launchData = null;

        RewardClaimManager.get().startPoll("evt-1", "reward-1", expiringSession, launchData);
        RewardClaimManager.get().startPoll("evt-2", "reward-2", expiringSession, launchData);
        assertEquals(2, RewardClaimManager.get().inFlightCount());

        RewardClaimManager.get().cancelClaimsForSession(expiringSession);

        assertEquals(0, RewardClaimManager.get().inFlightCount());
    }

    @Test
    public void cancelClaimsForSession_preservesClaimsFromOtherSessions() throws Exception {
        final Session sessionA = makeSyntheticSession();
        final Session sessionB = makeSyntheticSession();
        final Teak.AttributedLaunchData launchData = null;

        RewardClaimManager.get().startPoll("evt-a", "reward-1", sessionA, launchData);
        RewardClaimManager.get().startPoll("evt-b", "reward-2", sessionB, launchData);
        assertEquals(2, RewardClaimManager.get().inFlightCount());

        RewardClaimManager.get().cancelClaimsForSession(sessionA);

        // sessionB's claim survives.
        assertEquals(1, RewardClaimManager.get().inFlightCount());
        assertEquals("evt-b", RewardClaimManager.get().inFlightEventIdsForTest().get(0));
    }

    // --- Expiring → Active flicker keeps polling alive ---

    @Test
    public void expiringFlicker_keepsPollingAlive_andExpiredCancelsIt() throws Exception {
        // Wire the manager's static listener; TeakUnitTest.@Before strips event listeners
        // every test, so we re-register inside the test body.
        RewardClaimManager.registerStaticEventListeners();

        final Session session = makeSyntheticSession();
        setCurrentSession(session);
        try {
            RewardClaimManager.get().startPoll("evt-1", "reward-1", session, null);
            assertEquals(1, RewardClaimManager.get().inFlightCount());

            // First scheduled poll fires.
            scheduler.runNext();
            assertEquals(1, sender.polls.size());

            // Expiring transition while a poll is in flight. An Expiring→Active flicker
            // (e.g., notification-center swipe) preserves the Session instance — so the
            // weak originating-session ref still matches and the manager must keep polling.
            io.teak.sdk.TeakEvent.postEvent(
                new io.teak.sdk.event.SessionStateEvent(session, Session.State.Expiring, Session.State.UserIdentified));
            waitForEventQueueToDrain();
            assertEquals("Expiring must not cancel an in-flight poll",
                1, RewardClaimManager.get().inFlightCount());

            // Server replies pending → onPollReply schedules the next poll. The reply-
            // landing stale check must pass (currentSession is still the originating
            // session, even though the state has been Expiring).
            sender.completeLastPoll(200, "{\"status\":\"pending\"}");
            scheduler.runNext();
            assertEquals("After Expiring, the next poll must still fire — flicker proof",
                2, sender.polls.size());

            // Expired transition (post-flicker timeout): cancellation fires.
            io.teak.sdk.TeakEvent.postEvent(
                new io.teak.sdk.event.SessionStateEvent(session, Session.State.Expired, Session.State.Expiring));
            waitForEventQueueToDrain();
            assertEquals("Expired must cancel polling", 0, RewardClaimManager.get().inFlightCount());
        } finally {
            setCurrentSession(null);
        }
    }

    private static void waitForEventQueueToDrain() throws InterruptedException {
        // TeakEvent dispatches asynchronously on a single-threaded executor. The handful of
        // SessionStateEvent postings under test settle in well under this budget.
        Thread.sleep(250);
    }

    // --- ack retry curve ---

    @Test
    public void ack_5xxTriggersRetryWithinBudget_thenExhaustsAndDrops() throws Exception {
        final Session session = makeSyntheticSession();
        final Teak.AttributedLaunchData launchData = null;

        RewardClaimManager.get().startPoll("evt-1", "reward-1", session, launchData);
        // Drive the first scheduled poll.
        scheduler.runNext();
        // Sender now has a poll request pending.
        sender.completeLastPoll(200, "{\"status\":\"completed\",\"reward\":{}}");

        // Resolved → ack scheduled at delay 0, run it.
        scheduler.runNext();
        assertEquals(1, sender.acks.size());

        // First ack: 500 → schedule retry.
        sender.completeLastAck(500, "");
        scheduler.runNext();
        assertEquals(2, sender.acks.size());
        assertEquals(1, RewardClaimManager.get().inFlightCount());

        // Second ack: 500 → schedule retry.
        sender.completeLastAck(500, "");
        scheduler.runNext();
        assertEquals(3, sender.acks.size());
        assertEquals(1, RewardClaimManager.get().inFlightCount());

        // Third ack: 500 → exhausted, claim dropped.
        sender.completeLastAck(500, "");
        assertEquals(0, RewardClaimManager.get().inFlightCount());
    }

    @Test
    public void ack_2xxAfterRetry_clearsEntry() throws Exception {
        final Session session = makeSyntheticSession();
        final Teak.AttributedLaunchData launchData = null;

        RewardClaimManager.get().startPoll("evt-1", "reward-1", session, launchData);
        scheduler.runNext();
        sender.completeLastPoll(200, "{\"status\":\"completed\",\"reward\":{}}");
        scheduler.runNext();
        assertEquals(1, sender.acks.size());

        sender.completeLastAck(503, "");
        scheduler.runNext();
        assertEquals(2, sender.acks.size());

        sender.completeLastAck(200, "{\"status\":\"completed\"}");
        // 2xx clears the entry even mid-retry.
        assertEquals(0, RewardClaimManager.get().inFlightCount());
    }

    // --- same-session-through-retry ---

    @Test
    public void ack_carriesOriginatingClickingUserId_evenAfterGlobalSessionRotation() throws Exception {
        final Session originatingSession = makeSyntheticSessionWithUser("player-original");
        setCurrentSession(originatingSession);
        try {
            RewardClaimManager.get().startPoll("evt-1", "reward-1", originatingSession, null);
            scheduler.runNext();
            sender.completeLastPoll(200, "{\"status\":\"completed\",\"reward\":{}}");
            // onPollReply ran synchronously: resolved event posted, ack scheduled.

            // Rotate the global session before the ack fires. A new Session instance with
            // a different user is what a logout/login swap or a fresh attributed launch
            // produces in production. The ack must continue to carry the *originating*
            // session's clicking_user_id by value, not late-bind to whatever
            // Session.currentSession resolves to at retry-send time.
            final Session rotatedSession = makeSyntheticSessionWithUser("player-different");
            setCurrentSession(rotatedSession);

            scheduler.runNext();
            assertEquals(1, sender.acks.size());
            assertEquals("player-original", sender.acks.get(0).clickingUserId);

            // Drive a 5xx retry under the same rotated current-session: still the
            // originating clicking_user_id.
            sender.completeLastAck(500, "");
            scheduler.runNext();
            assertEquals(2, sender.acks.size());
            assertEquals("player-original", sender.acks.get(1).clickingUserId);
        } finally {
            setCurrentSession(null);
        }
    }

    // --- two-reward-id-flavors coexistence on the resolved event ---

    @Test
    public void resolvedEvent_carriesBothCamelCaseAttributionAndSnakeCaseGrant() throws Exception {
        // Distinct values on each axis: teakRewardId (camelCase) is what this launch was
        // attributed to from the URL; teak_reward_id (snake_case) is what the server
        // authoritatively granted on this click. Proxy-reward routes can return a
        // different reward id than the URL's attribution; both must surface to the host.
        final Teak.AttributedLaunchData launchData = makeFakeLaunchData("attribution-id");
        final Session session = makeSyntheticSession();
        setCurrentSession(session);
        try {
            clearEventBusQueue();
            RewardClaimManager.get().startPoll("evt-1", "attribution-id", session, launchData);
            scheduler.runNext();
            sender.completeLastPoll(200,
                "{\"status\":\"completed\",\"teak_reward_id\":\"server-authoritative-id\",\"reward\":{}}");

            // The resolved event posts to userIdReadyEventBusQueue (currentSession is set
            // but state isn't UserIdentified here, so it queues rather than dispatches).
            final Teak.RewardClaimResolvedEvent resolved = findResolvedEvent();
            assertNotNull("RewardClaimResolvedEvent should have been queued", resolved);

            final io.teak.sdk.json.JSONObject payload = resolved.toJSON();
            assertEquals("attribution-id", payload.getString("teakRewardId"));
            assertEquals("server-authoritative-id", payload.getString("teak_reward_id"));
        } finally {
            setCurrentSession(null);
        }
    }

    private static Teak.AttributedLaunchData makeFakeLaunchData(String teakRewardId) {
        final android.net.Uri uri = org.mockito.Mockito.mock(android.net.Uri.class);
        org.mockito.Mockito.when(uri.isOpaque()).thenReturn(false);
        org.mockito.Mockito.when(uri.isHierarchical()).thenReturn(true);
        // toString flows through DeepLink.willProcessUri -> URI.create, which rejects
        // Mockito's default "Mock for Uri" toString. Stub a valid URI string.
        org.mockito.Mockito.when(uri.toString()).thenReturn("teak123://launch");
        org.mockito.Mockito.when(uri.getQueryParameter("teak_reward_id")).thenReturn(teakRewardId);
        org.mockito.Mockito.when(uri.getQueryParameter("teak_channel_name")).thenReturn("android_push");
        org.mockito.Mockito.when(uri.getQueryParameter("teak_creative_name")).thenReturn("fixture-creative");
        org.mockito.Mockito.when(uri.getQueryParameter("teak_creative_id")).thenReturn("fixture-creative-id");
        org.mockito.Mockito.when(uri.getQueryParameter("teak_schedule_name")).thenReturn(null);
        org.mockito.Mockito.when(uri.getQueryParameter("teak_schedule_id")).thenReturn(null);
        org.mockito.Mockito.when(uri.getQueryParameter("teak_deep_link")).thenReturn(null);
        org.mockito.Mockito.when(uri.getQueryParameter("teak_opt_out_category")).thenReturn(null);
        return new Teak.RewardlinkLaunchData(uri, null);
    }

    @SuppressWarnings("unchecked")
    private static void clearEventBusQueue() throws Exception {
        final java.lang.reflect.Field f = Session.class.getDeclaredField("userIdReadyEventBusQueue");
        f.setAccessible(true);
        ((List<Object>) f.get(null)).clear();
    }

    @SuppressWarnings("unchecked")
    private static Teak.RewardClaimResolvedEvent findResolvedEvent() throws Exception {
        final java.lang.reflect.Field f = Session.class.getDeclaredField("userIdReadyEventBusQueue");
        f.setAccessible(true);
        for (Object o : (List<Object>) f.get(null)) {
            if (o instanceof Teak.RewardClaimResolvedEvent) {
                return (Teak.RewardClaimResolvedEvent) o;
            }
        }
        return null;
    }

    // --- helpers ---

    private static Session makeSyntheticSession() throws Exception {
        // Session has no public constructor accessible from outside io.teak.sdk.core, but
        // the package-private "Null Session" sentinel constructor is reachable via
        // reflection. Each call instantiates a fresh distinct Session so identity-based
        // staleness checks see them as unrelated.
        return makeSyntheticSessionWithUser("synthetic-user");
    }

    private static Session makeSyntheticSessionWithUser(String userId) throws Exception {
        final Constructor<Session> ctor = Session.class.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        final Session session = ctor.newInstance("synthetic:" + userId);
        // Stamp the userId so originatingClickingUserId snapshots it.
        try {
            final java.lang.reflect.Field userField = Session.class.getDeclaredField("userId");
            userField.setAccessible(true);
            userField.set(session, userId);
        } catch (Exception ignored) {
        }
        return session;
    }

    /**
     * Set the static {@code Session.currentSession} field so {@link Session#getCurrentSessionOrNull()}
     * returns it. The field is private; reflection is the only way in from the test package.
     */
    private static void setCurrentSession(Session session) throws Exception {
        final java.lang.reflect.Field f = Session.class.getDeclaredField("currentSession");
        f.setAccessible(true);
        f.set(null, session);
    }


    /**
     * Records every poll/ack call so the test can inspect parameters and complete them on
     * its own schedule.
     */
    private static class RecordingSender implements RewardClaimManager.ClaimRequestSender {
        static class Call {
            final String eventId;
            final String teakAppId;
            final String clickingUserId;
            final RewardClaimManager.ReplyHandler handler;
            Call(String eventId, String teakAppId, String clickingUserId,
                RewardClaimManager.ReplyHandler handler) {
                this.eventId = eventId;
                this.teakAppId = teakAppId;
                this.clickingUserId = clickingUserId;
                this.handler = handler;
            }
        }

        final List<Call> polls = new ArrayList<>();
        final List<Call> acks = new ArrayList<>();

        @Override
        public void sendPoll(String eventId, String teakAppId, String clickingUserId,
            RewardClaimManager.ReplyHandler handler) {
            polls.add(new Call(eventId, teakAppId, clickingUserId, handler));
        }

        @Override
        public void sendAck(String eventId, String teakAppId, String clickingUserId,
            RewardClaimManager.ReplyHandler handler) {
            acks.add(new Call(eventId, teakAppId, clickingUserId, handler));
        }

        void completeLastPoll(int statusCode, String body) {
            polls.get(polls.size() - 1).handler.onReply(statusCode, body);
        }

        void completeLastAck(int statusCode, String body) {
            acks.get(acks.size() - 1).handler.onReply(statusCode, body);
        }
    }
}
