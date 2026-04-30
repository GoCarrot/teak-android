package io.teak.app.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakNotification;
import io.teak.sdk.core.RewardClaimManager;
import io.teak.sdk.core.Session;
import io.teak.sdk.json.JSONObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that {@link TeakNotification.Reward#dispatchClickReply} branches the click reply
 * by status:
 *
 * <ul>
 *   <li>{@code grant_reward}, {@code claim_mode_unsupported}, and unknown statuses fall
 *       onto the legacy {@link Teak.RewardClaimEvent} surface.</li>
 *   <li>{@code token_issued} fires {@link Teak.RewardJwtIssuedEvent}.</li>
 *   <li>{@code claim_pending} (with an event_id) fires {@link Teak.RewardClaimPendingEvent}
 *       and starts a poll on the manager.</li>
 * </ul>
 */
// The Uri stub helper sets up every queryParameter the AttributedLaunchData constructor
// reads. Strict-mode Mockito would flag stubs that some tests don't exercise; .Silent
// preserves stubbing semantics without that lint.
@RunWith(MockitoJUnitRunner.Silent.class)
public class RewardClickReplyDispatchTest extends TeakUnitTest {

    private static final Field userIdReadyEventBusQueueField;
    private static final Method dispatchClickReply;
    private static final Constructor<TeakNotification.Reward> rewardCtor;

    static {
        try {
            userIdReadyEventBusQueueField = Session.class.getDeclaredField("userIdReadyEventBusQueue");
            userIdReadyEventBusQueueField.setAccessible(true);

            dispatchClickReply = TeakNotification.Reward.class.getDeclaredMethod(
                "dispatchClickReply",
                TeakNotification.Reward.class,
                String.class,
                Teak.AttributedLaunchData.class,
                Session.class,
                JSONObject.class);
            dispatchClickReply.setAccessible(true);

            rewardCtor = TeakNotification.Reward.class.getDeclaredConstructor(JSONObject.class);
            rewardCtor.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void resetState() throws Exception {
        clearEventQueue();
        RewardClaimManager.get().resetForTest();
    }

    @After
    public void clear() throws Exception {
        clearEventQueue();
        RewardClaimManager.get().resetForTest();
    }

    @Test
    public void dispatch_grantReward_firesLegacyRewardClaimEvent() throws Exception {
        final JSONObject reply = makeReply("grant_reward");
        dispatch(reply);

        final List<Object> events = capturedEvents();
        assertEquals(1, events.size());
        assertTrue("expected legacy RewardClaimEvent, got " + events.get(0).getClass(),
            events.get(0) instanceof Teak.RewardClaimEvent);
    }

    @Test
    public void dispatch_claimModeUnsupported_firesLegacyRewardClaimEvent() throws Exception {
        final JSONObject reply = makeReply("claim_mode_unsupported");
        dispatch(reply);

        final List<Object> events = capturedEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof Teak.RewardClaimEvent);
    }

    @Test
    public void dispatch_tokenIssued_firesRewardJwtIssuedEvent_andDoesNotStartPoll() throws Exception {
        final JSONObject reply = new JSONObject();
        reply.put("status", "token_issued");
        reply.put("token", "ey.fakejwt.signature");
        reply.put("reward", new JSONObject());

        dispatch(reply);

        final List<Object> events = capturedEvents();
        assertEquals(1, events.size());
        assertTrue("expected RewardJwtIssuedEvent, got " + events.get(0).getClass(),
            events.get(0) instanceof Teak.RewardJwtIssuedEvent);

        // No poll started for JWT mode — the host game claims against its own backend.
        assertEquals(0, RewardClaimManager.get().inFlightCount());
    }

    @Test
    public void dispatch_claimPending_firesPendingEvent_andStartsPoll() throws Exception {
        final JSONObject reply = new JSONObject();
        reply.put("status", "claim_pending");
        reply.put("event_id", "abc-123");
        reply.put("reward", new JSONObject());

        dispatch(reply);

        final List<Object> events = capturedEvents();
        assertEquals(1, events.size());
        assertTrue("expected RewardClaimPendingEvent, got " + events.get(0).getClass(),
            events.get(0) instanceof Teak.RewardClaimPendingEvent);

        final Teak.RewardClaimPendingEvent event = (Teak.RewardClaimPendingEvent) events.get(0);
        assertEquals("abc-123", event.eventId);

        // Poll lifecycle has begun.
        assertEquals(1, RewardClaimManager.get().inFlightCount());
        assertEquals("abc-123", RewardClaimManager.get().inFlightEventIdsForTest().get(0));
    }

    @Test
    public void dispatch_claimPendingMissingEventId_fallsBackToLegacy() throws Exception {
        final JSONObject reply = new JSONObject();
        reply.put("status", "claim_pending");
        // No event_id — server gave us no poll handle, so we fall back to the legacy
        // event surface so the host game at least observes that something happened.
        dispatch(reply);

        final List<Object> events = capturedEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof Teak.RewardClaimEvent);
        assertEquals(0, RewardClaimManager.get().inFlightCount());
    }

    @Test
    public void dispatch_unknownStatus_fallsBackToLegacy() throws Exception {
        final JSONObject reply = makeReply("brand_new_status_2099");
        dispatch(reply);

        final List<Object> events = capturedEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof Teak.RewardClaimEvent);
    }

    @Test
    public void dispatch_grantReward_eventCarriesLaunchDataAndRewardProvenanceTogether() throws Exception {
        final JSONObject reply = new JSONObject();
        reply.put("status", "grant_reward");
        reply.put("teak_reward_id", "server-authoritative-id"); // snake_case from server

        final Teak.AttributedLaunchData launchData = makeFakeLaunchData("attributed-id");
        final TeakNotification.Reward reward = rewardCtor.newInstance(makeReplyForReward("grant_reward"));

        dispatchClickReply.invoke(null, reward, "attributed-id", launchData, makeSession(), reply);

        final List<Object> events = capturedEvents();
        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof Teak.RewardClaimEvent);
        // The legacy event surface continues to carry the launch data; the per-event
        // toJSON merge — separately tested via cross-SDK conventions — places camelCase
        // teakRewardId from launch data alongside snake_case teak_reward_id from reply.
        assertSame(launchData, ((Teak.RewardClaimEvent) events.get(0)).launchData);
    }

    // --- helpers ---

    private static JSONObject makeReply(String status) {
        final JSONObject reply = new JSONObject();
        reply.put("status", status);
        return reply;
    }

    private static JSONObject makeReplyForReward(String status) {
        final JSONObject reply = new JSONObject();
        reply.put("status", status);
        reply.put("teakRewardId", "attributed-id");
        return reply;
    }

    private static void dispatch(JSONObject reply) throws Exception {
        final TeakNotification.Reward reward = rewardCtor.newInstance(makeReplyForReward(reply.getString("status")));
        final Teak.AttributedLaunchData launchData = makeFakeLaunchData("attributed-id");
        dispatchClickReply.invoke(null, reward, "attributed-id", launchData, makeSession(), reply);
    }

    private static Teak.AttributedLaunchData makeFakeLaunchData(String teakRewardId) throws Exception {
        // RewardlinkLaunchData(Uri uri, Uri shortLink) reads query params off uri. In the
        // unit-test JVM, android.net.Uri is a Mockito mock since android.jar is the stub
        // jar — so we mock the calls the constructor makes.
        final android.net.Uri uri = org.mockito.Mockito.mock(android.net.Uri.class);
        org.mockito.Mockito.when(uri.isOpaque()).thenReturn(false);
        org.mockito.Mockito.when(uri.isHierarchical()).thenReturn(true);
        // Click-time path now flattens launchData into the eleven-key attribution map at
        // startPoll, which routes through DeepLink.willProcessUri → URI.create(toString()).
        // Default Mockito toString ("Mock for Uri, hashCode: ...") fails URI.create; stub
        // a real URI string.
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

    private static Session makeSession() throws Exception {
        final Constructor<Session> ctor = Session.class.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        final Session s = ctor.newInstance("synthetic:dispatch-test");
        try {
            final Field userField = Session.class.getDeclaredField("userId");
            userField.setAccessible(true);
            userField.set(s, "test-user");
        } catch (Exception ignored) {
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> capturedEvents() throws Exception {
        return new ArrayList<>((List<Object>) userIdReadyEventBusQueueField.get(null));
    }

    @SuppressWarnings("unchecked")
    private static void clearEventQueue() throws Exception {
        ((List<Object>) userIdReadyEventBusQueueField.get(null)).clear();
    }
}
