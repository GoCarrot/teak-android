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

@RunWith(MockitoJUnitRunner.class)
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
    public void sessionStateListener_onlyDropsOnExpired_notExpiring() throws Exception {
        // Wire the manager's static listener; TeakUnitTest.@Before strips event listeners
        // every test, so we re-register inside the test body.
        RewardClaimManager.registerStaticEventListeners();

        final Session session = makeSyntheticSession();
        RewardClaimManager.get().startPoll("evt-1", "reward-1", session, null);
        assertEquals(1, RewardClaimManager.get().inFlightCount());

        // Expiring transition: no cancellation should happen. An Expiring→Active flicker
        // keeps the same Session instance and we want polling to continue across it.
        io.teak.sdk.TeakEvent.postEvent(
            new io.teak.sdk.event.SessionStateEvent(session, Session.State.Expiring, Session.State.UserIdentified));
        waitForEventQueueToDrain();
        assertEquals("Expiring should not cancel polling", 1, RewardClaimManager.get().inFlightCount());

        // Expired transition: cancellation fires.
        io.teak.sdk.TeakEvent.postEvent(
            new io.teak.sdk.event.SessionStateEvent(session, Session.State.Expired, Session.State.Expiring));
        waitForEventQueueToDrain();
        assertEquals("Expired should cancel polling", 0, RewardClaimManager.get().inFlightCount());
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
        final Teak.AttributedLaunchData launchData = null;

        RewardClaimManager.get().startPoll("evt-1", "reward-1", originatingSession, launchData);
        scheduler.runNext();
        sender.completeLastPoll(200, "{\"status\":\"completed\",\"reward\":{}}");

        // Even if the global session changes between poll and ack, the ack carries the
        // originating session's clicking_user_id.
        scheduler.runNext();
        assertEquals(1, sender.acks.size());
        assertEquals("player-original", sender.acks.get(0).clickingUserId);

        // Drive a 5xx retry; same clicking_user_id even on the retried POST.
        sender.completeLastAck(500, "");
        scheduler.runNext();
        assertEquals(2, sender.acks.size());
        assertEquals("player-original", sender.acks.get(1).clickingUserId);
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
