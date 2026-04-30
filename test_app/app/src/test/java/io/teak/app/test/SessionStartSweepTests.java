package io.teak.app.test;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.teak.sdk.Teak;
import io.teak.sdk.core.RewardClaimManager;
import io.teak.sdk.core.Session;
import io.teak.sdk.json.JSONArray;
import io.teak.sdk.json.JSONObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Spec for the session-start /claims sweep dispatcher. Pins the contract independent of the
 * wire layer: {@code dispatchSweptClaims(JSONArray, Session)} is a wire-free seam exercising
 * per-claim enrollment, dedupe vs the click-time path, terminal/pending branching, and
 * tolerance to malformed entries and string-encoded {@code session_attribution} blobs.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class SessionStartSweepTests extends TeakUnitTest {

    private RecordingSender sender;
    private DeterministicScheduler scheduler;

    @Before
    public void resetManager() throws Exception {
        RewardClaimManager.get().resetForTest();
        sender = new RecordingSender();
        scheduler = new DeterministicScheduler();
        RewardClaimManager.get().setSenderForTest(sender);
        RewardClaimManager.get().setSchedulerForTest(scheduler);
        clearEventBusQueue();
    }

    @After
    public void clearManager() throws Exception {
        RewardClaimManager.get().resetForTest();
        RewardClaimManager.get().setSenderForTest(null);
        clearEventBusQueue();
        setCurrentSession(null);
    }

    // --- Resolved event payload from sweep (attribution-map merge) ---

    /// The dict-taking RewardClaimResolvedEvent constructor merges the eleven-key attribution
    /// map and the wire reply at fire time. Reply wins on key collision — same merge order
    /// as the launchData-flattened click-time path. The two reward-id flavors (teakRewardId
    /// attribution vs. teak_reward_id authoritative) are tracked distinctly: teakRewardId
    /// surfaces on the userInfo, teak_reward_id is in the strip set.
    @Test
    public void resolvedEvent_fromAttributionMap_mergesAttributionAndReply() throws Exception {
        final Map<String, Object> attribution = new HashMap<>();
        attribution.put("launch_link", "teaktest-app://chest");
        attribution.put("teakNotifId", "2048153148060669486");
        attribution.put("teakScheduleId", "2046986133304291328");
        attribution.put("teakScheduleName", "daily_promo_2026q2");
        attribution.put("teakCreativeId", "2046986561123301779");
        attribution.put("teakCreativeName", "summer_sale_v3");
        attribution.put("teakRewardId", "2048153148060669138");
        attribution.put("teakChannelName", "android_push");
        attribution.put("teakDeepLink", null);
        attribution.put("teakOptOutCategory", "teak");
        attribution.put("teakSystemActivityId", null);

        final JSONObject reply = new JSONObject();
        reply.put("event_id", "evt-resurfaced-1");
        reply.put("status", "completed");
        reply.put("reward", new JSONObject(new HashMap<String, Object>() {{
            put("gems", 25);
        } }));
        reply.put("customer_status_code", 200);
        reply.put("teak_reward_id", "2048153148060669999");
        reply.put("created_at", "2026-04-29T20:15:00Z");
        reply.put("completed_at", "2026-04-29T20:15:04Z");

        final Teak.RewardClaimResolvedEvent event =
            new Teak.RewardClaimResolvedEvent(attribution, "evt-resurfaced-1", reply);

        final JSONObject payload = event.toJSON();
        assertEquals("evt-resurfaced-1", payload.getString("event_id"));
        assertEquals("completed", payload.getString("status"));
        assertEquals(200, payload.getInt("customer_status_code"));

        assertEquals("2048153148060669486", payload.getString("teakNotifId"));
        assertEquals("2046986133304291328", payload.getString("teakScheduleId"));
        assertEquals("summer_sale_v3", payload.getString("teakCreativeName"));
        assertEquals("android_push", payload.getString("teakChannelName"));
        assertEquals("2048153148060669138", payload.getString("teakRewardId"));

        assertFalse("teak_reward_id (server-authoritative grant id) must NOT appear on the resolved-event userInfo",
            payload.has("teak_reward_id"));
        assertFalse("raw session_attribution blob must NOT appear on the resolved-event userInfo",
            payload.has("session_attribution"));

        // CEO ruling on cross-SDK strip set (post-iOS-C-724 / post-JS-C-725): created_at
        // and completed_at are KEPT — they're documented timing fields, not server
        // bookkeeping. wire_format.md is canonical; iOS TeakClaimPoll.m and JS teak.coffee
        // both strip only {session_attribution, teak_reward_id}.
        assertEquals("created_at must pass through to the resolved-event userInfo",
            "2026-04-29T20:15:00Z", payload.getString("created_at"));
        assertEquals("completed_at must pass through to the resolved-event userInfo",
            "2026-04-29T20:15:04Z", payload.getString("completed_at"));

        assertTrue("teakDeepLink must arrive as JSON null when unset, not absent",
            payload.isNull("teakDeepLink"));
        assertTrue("teakSystemActivityId must arrive as JSON null on Android (always-null platform)",
            payload.isNull("teakSystemActivityId"));
    }

    // --- Sweep dispatch: pending vs terminal branching ---

    /// Pending sweep entries enroll into the in-flight dictionary so the existing poll
    /// scheduler picks them up. The cross-SDK contract: pending claims surfaced by the sweep
    /// continue with the same poll loop the click-time path uses, no parallel implementation.
    @Test
    public void sweep_registersPendingClaimInInflightDictionary() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        final JSONArray claims = new JSONArray();
        claims.put(buildClaim("evt-sweep-pending-1", "pending",
            buildAttribution("2048153148060669486", "2048153148060669138", "android_push"),
            null));

        RewardClaimManager.get().dispatchSweptClaims(claims, session);

        assertEquals(1, RewardClaimManager.get().inFlightCount());
        assertTrue(RewardClaimManager.get().inFlightEventIdsForTest().contains("evt-sweep-pending-1"));
    }

    /// Terminal sweep entries also enroll into the in-flight dictionary so the existing
    /// retriable /claim_ack machinery (with its bounded retry budget and originating-session
    /// pin) handles ack for the resurfaced claim.
    @Test
    public void sweep_registersTerminalClaimInInflightDictionary() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        final JSONObject reward = new JSONObject();
        reward.put("gems", 25);

        final JSONArray claims = new JSONArray();
        claims.put(buildClaim("evt-sweep-terminal-1", "completed",
            buildAttribution("2048153148060669486", null, null), reward));

        RewardClaimManager.get().dispatchSweptClaims(claims, session);

        assertEquals(1, RewardClaimManager.get().inFlightCount());
        assertTrue(RewardClaimManager.get().inFlightEventIdsForTest().contains("evt-sweep-terminal-1"));
    }

    /// Terminal sweep entries fire {@link Teak.RewardClaimResolvedEvent} via the
    /// whenUserIdIsReadyPost queue — same surface the click-time poll uses on terminal
    /// reply. The host-game-facing contract: a single canonical resolved-event shape
    /// regardless of source (poll vs sweep).
    @Test
    public void sweep_terminalEntry_firesRewardClaimResolvedEvent() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        final JSONObject reward = new JSONObject();
        reward.put("gems", 25);

        final JSONArray claims = new JSONArray();
        claims.put(buildClaim("evt-sweep-fire-1", "completed",
            buildAttribution("2048153148060669486", "2048153148060669138", "android_push"),
            reward));

        RewardClaimManager.get().dispatchSweptClaims(claims, session);

        final Teak.RewardClaimResolvedEvent resolved = findResolvedEvent("evt-sweep-fire-1");
        assertNotNull("terminal sweep entry must enqueue RewardClaimResolvedEvent", resolved);

        final JSONObject payload = resolved.toJSON();
        assertEquals("evt-sweep-fire-1", payload.getString("event_id"));
        assertEquals("completed", payload.getString("status"));
        assertEquals("2048153148060669486", payload.getString("teakNotifId"));
        assertEquals("2048153148060669138", payload.getString("teakRewardId"));
        assertEquals("android_push", payload.getString("teakChannelName"));
    }

    // --- Sweep dispatch: idempotency vs click-time path ---

    /// A claim that's already in flight from a click-time start MUST NOT be re-entered by
    /// the sweep. The dedupe guard is the existing in-flight dictionary keyed on event_id.
    @Test
    public void sweep_skipsClaimAlreadyInFlightFromClickTime() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        // Click-time start.
        RewardClaimManager.get().startPoll("evt-clicktime-already-1", "reward-1", session,
            (Teak.AttributedLaunchData) null);
        assertEquals(1, RewardClaimManager.get().inFlightCount());

        // Sweep with the same event_id — terminal. Must be a no-op for enrollment.
        final JSONArray claims = new JSONArray();
        claims.put(buildClaim("evt-clicktime-already-1", "completed",
            buildAttribution("2048153148060669486", null, null), new JSONObject()));

        RewardClaimManager.get().dispatchSweptClaims(claims, session);

        assertEquals("sweep dedupe must keep the in-flight dictionary at one entry",
            1, RewardClaimManager.get().inFlightCount());
    }

    // --- Sweep dispatch: pending does NOT re-fire ClaimPending ---

    /// Per cross-SDK conventions: ClaimPending is a point-in-time event, not a history-replay
    /// event. The sweep enrolls pending entries into the poll loop without re-firing
    /// {@link Teak.RewardClaimPendingEvent}. Host games that listened on click-time already
    /// saw the optimistic surface; resurfaced pendings just continue polling silently until
    /// terminal.
    @Test
    public void sweep_pendingEntry_doesNotFireRewardClaimPendingEvent() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        final JSONArray claims = new JSONArray();
        claims.put(buildClaim("evt-sweep-pending-silent-1", "pending",
            buildAttribution("2048153148060669486", null, null), null));

        RewardClaimManager.get().dispatchSweptClaims(claims, session);

        assertNull("sweep must never fire RewardClaimPendingEvent — Pending is point-in-time only",
            findEventOfType(Teak.RewardClaimPendingEvent.class));
    }

    // --- Sweep dispatch: input tolerance ---

    /// An empty claims list is a valid response shape (the user has no unacked claims) —
    /// sweep dispatcher must handle it without enrolling any entries or crashing.
    @Test
    public void sweep_emptyClaimsList_doesNotCrashAndDoesNotEnroll() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        RewardClaimManager.get().dispatchSweptClaims(new JSONArray(), session);

        assertEquals(0, RewardClaimManager.get().inFlightCount());
    }

    /// A malformed individual claim entry (missing event_id) is dropped without taking the
    /// rest of the list down. The dispatcher iterates defensively so a single server-side
    /// anomaly doesn't strand the user's other unacked claims.
    @Test
    public void sweep_skipsMalformedEntriesAndDispatchesRest() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        // Missing event_id, empty event_id, and a valid one.
        final JSONArray claims = new JSONArray();
        {
            final JSONObject missingEventId = new JSONObject();
            missingEventId.put("status", "completed");
            claims.put(missingEventId);
        }
        claims.put(buildClaim("evt-good-1", "pending", buildAttribution("notif-1", null, null), null));
        {
            final JSONObject emptyEventId = new JSONObject();
            emptyEventId.put("event_id", "");
            emptyEventId.put("status", "completed");
            claims.put(emptyEventId);
        }

        RewardClaimManager.get().dispatchSweptClaims(claims, session);

        assertEquals("only the well-formed claim should be enrolled",
            1, RewardClaimManager.get().inFlightCount());
        assertTrue(RewardClaimManager.get().inFlightEventIdsForTest().contains("evt-good-1"));
    }

    // --- Sweep dispatch: session_attribution unpack tolerance ---

    /// session_attribution may arrive as a JSON-encoded string (mirrors the click-POST mint
    /// shape) or as an inline object. The sweep dispatcher tolerates both forms so the SDK
    /// is robust to either taro-side encoding choice.
    @Test
    public void sweep_unpacksSessionAttributionFromJsonString() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        // Build the attribution as a JSON-encoded string and embed it as the
        // session_attribution field of the claim entry.
        final JSONObject attributionInline = new JSONObject();
        attributionInline.put("teakNotifId", "2048153148060669486");
        attributionInline.put("teakRewardId", "2048153148060669138");
        attributionInline.put("teakChannelName", "android_push");
        final String attributionString = attributionInline.toString();

        final JSONObject claim = new JSONObject();
        claim.put("event_id", "evt-sweep-string-attr-1");
        claim.put("status", "completed");
        claim.put("session_attribution", attributionString);
        claim.put("reward", new JSONObject());

        final JSONArray claims = new JSONArray();
        claims.put(claim);

        RewardClaimManager.get().dispatchSweptClaims(claims, session);

        // Enrollment proves the string form was accepted.
        assertTrue("sweep must accept session_attribution as a JSON-encoded string",
            RewardClaimManager.get().inFlightEventIdsForTest().contains("evt-sweep-string-attr-1"));

        // The resolved event must surface attribution keys derived from the string-form
        // unpack — proves the JSON-decode happened, not just a tolerated-but-ignored input.
        final Teak.RewardClaimResolvedEvent resolved = findResolvedEvent("evt-sweep-string-attr-1");
        assertNotNull(resolved);
        final JSONObject payload = resolved.toJSON();
        assertEquals("2048153148060669486", payload.getString("teakNotifId"));
        assertEquals("2048153148060669138", payload.getString("teakRewardId"));
        assertEquals("android_push", payload.getString("teakChannelName"));
    }

    /// A non-JSON string in {@code session_attribution} is treated as no-attribution rather
    /// than failing the whole entry — the dispatcher's defensive catch keeps a single
    /// server-side anomaly from stranding the claim. The claim still enrolls and the
    /// resolved event still fires, just without the eleven attribution keys populated from
    /// the (un-decodable) blob.
    @Test
    public void sweep_malformedSessionAttributionString_isToleratedAsEmptyAttribution() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        final JSONObject claim = new JSONObject();
        claim.put("event_id", "evt-sweep-bad-string-1");
        claim.put("status", "completed");
        claim.put("session_attribution", "not even close to json {");
        claim.put("reward", new JSONObject());

        final JSONArray claims = new JSONArray();
        claims.put(claim);

        RewardClaimManager.get().dispatchSweptClaims(claims, session);

        // The claim still enrolls; the parse failure is dead-letter, not poison-pill.
        assertTrue(RewardClaimManager.get().inFlightEventIdsForTest().contains("evt-sweep-bad-string-1"));

        // Resolved event still fires; attribution is empty, so the eleven keys flow through
        // as JSON null on the always-present contract. Reply keys still present.
        final Teak.RewardClaimResolvedEvent resolved = findResolvedEvent("evt-sweep-bad-string-1");
        assertNotNull(resolved);
        final JSONObject payload = resolved.toJSON();
        assertEquals("evt-sweep-bad-string-1", payload.getString("event_id"));
        assertEquals("completed", payload.getString("status"));
    }

    // --- startSweep HTTP-boundary error branches ---

    /// {@code startSweep} short-circuits when the originating session has no user id —
    /// the {@code GET /claims} request is keyed on {@code clicking_user_id}, so there's
    /// nothing to send.
    @Test
    public void startSweep_skipsWhenSessionHasNoUserId() throws Exception {
        final Constructor<Session> ctor = Session.class.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        // No userId field-stamp: userId() returns null on this session.
        final Session sessionNoUser = ctor.newInstance("synthetic:no-user");

        RewardClaimManager.get().startSweep(sessionNoUser);

        assertEquals("startSweep must not invoke the sender when there is no user id",
            0, sender.sweeps.size());
    }

    /// A non-2xx response from {@code GET /claims} is treated as no claims to surface this
    /// launch — the at-least-once contract on RewardClaimResolvedEvent re-tries on the
    /// next session start. Nothing should enroll into the in-flight dictionary.
    @Test
    public void startSweep_non2xxResponse_doesNotEnroll() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        RewardClaimManager.get().startSweep(session);
        assertEquals("startSweep must invoke the sender once", 1, sender.sweeps.size());

        final RewardClaimManager.ReplyHandler handler = (RewardClaimManager.ReplyHandler) sender.sweeps.get(0)[2];
        handler.onReply(503, "{\"claims\":[{\"event_id\":\"evt-must-not-enroll\",\"status\":\"completed\"}]}");

        assertEquals("non-2xx must not enroll any claims",
            0, RewardClaimManager.get().inFlightCount());
    }

    /// A null response body is treated as no claims to surface — same at-least-once
    /// fallback to next session start.
    @Test
    public void startSweep_nullBody_doesNotEnroll() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        RewardClaimManager.get().startSweep(session);
        assertEquals(1, sender.sweeps.size());

        final RewardClaimManager.ReplyHandler handler = (RewardClaimManager.ReplyHandler) sender.sweeps.get(0)[2];
        handler.onReply(200, null);

        assertEquals(0, RewardClaimManager.get().inFlightCount());
    }

    /// An unparseable body (not JSON, or no {@code claims} key) is treated as no claims
    /// to surface — defensive against server-side response-shape drift.
    @Test
    public void startSweep_unparseableOrMissingClaimsKey_doesNotEnroll() throws Exception {
        final Session session = makeSyntheticSession();
        setCurrentSession(session);

        // Unparseable body.
        RewardClaimManager.get().startSweep(session);
        ((RewardClaimManager.ReplyHandler) sender.sweeps.get(0)[2]).onReply(200, "not json");
        assertEquals(0, RewardClaimManager.get().inFlightCount());

        // Valid JSON but no claims key.
        RewardClaimManager.get().startSweep(session);
        ((RewardClaimManager.ReplyHandler) sender.sweeps.get(1)[2]).onReply(200, "{\"other_field\":\"value\"}");
        assertEquals(0, RewardClaimManager.get().inFlightCount());
    }

    // --- helpers ---

    private static JSONObject buildAttribution(String teakNotifId, String teakRewardId,
        String teakChannelName) {
        final JSONObject attr = new JSONObject();
        // Eleven-key always-present contract: every key carries either a string value or
        // JSON null. Tests pass null for unset keys; JSONObject stores them explicitly so
        // the read-side dict-or-string unpack sees the same shape.
        attr.put("launch_link", JSONObject.NULL);
        attr.put("teakScheduleName", JSONObject.NULL);
        attr.put("teakScheduleId", JSONObject.NULL);
        attr.put("teakCreativeName", JSONObject.NULL);
        attr.put("teakCreativeId", JSONObject.NULL);
        attr.put("teakRewardId", teakRewardId == null ? JSONObject.NULL : teakRewardId);
        attr.put("teakChannelName", teakChannelName == null ? JSONObject.NULL : teakChannelName);
        attr.put("teakDeepLink", JSONObject.NULL);
        attr.put("teakOptOutCategory", JSONObject.NULL);
        attr.put("teakNotifId", teakNotifId == null ? JSONObject.NULL : teakNotifId);
        attr.put("teakSystemActivityId", JSONObject.NULL);
        return attr;
    }

    private static JSONObject buildClaim(String eventId, String status, JSONObject attribution,
        JSONObject reward) {
        final JSONObject claim = new JSONObject();
        claim.put("event_id", eventId);
        claim.put("status", status);
        claim.put("session_attribution", attribution);
        if (reward != null) {
            claim.put("reward", reward);
        }
        return claim;
    }

    private static Session makeSyntheticSession() throws Exception {
        return makeSyntheticSessionWithUser("synthetic-user");
    }

    private static Session makeSyntheticSessionWithUser(String userId) throws Exception {
        final Constructor<Session> ctor = Session.class.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        final Session session = ctor.newInstance("synthetic:" + userId);
        try {
            final Field userField = Session.class.getDeclaredField("userId");
            userField.setAccessible(true);
            userField.set(session, userId);
        } catch (Exception ignored) {
        }
        return session;
    }

    private static void setCurrentSession(Session session) throws Exception {
        final Field f = Session.class.getDeclaredField("currentSession");
        f.setAccessible(true);
        f.set(null, session);
    }

    @SuppressWarnings("unchecked")
    private static void clearEventBusQueue() throws Exception {
        final Field f = Session.class.getDeclaredField("userIdReadyEventBusQueue");
        f.setAccessible(true);
        ((List<Object>) f.get(null)).clear();
    }

    @SuppressWarnings("unchecked")
    private static Teak.RewardClaimResolvedEvent findResolvedEvent(String eventId) throws Exception {
        final Field f = Session.class.getDeclaredField("userIdReadyEventBusQueue");
        f.setAccessible(true);
        for (Object o : (List<Object>) f.get(null)) {
            if (o instanceof Teak.RewardClaimResolvedEvent && eventId.equals(((Teak.RewardClaimResolvedEvent) o).eventId)) {
                return (Teak.RewardClaimResolvedEvent) o;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T findEventOfType(Class<T> klass) throws Exception {
        final Field f = Session.class.getDeclaredField("userIdReadyEventBusQueue");
        f.setAccessible(true);
        for (Object o : (List<Object>) f.get(null)) {
            if (klass.isInstance(o)) {
                return (T) o;
            }
        }
        return null;
    }

    /**
     * Records sweep / poll / ack calls so terminal-firing tests don't blow up on a missing
     * sender. The dispatcher schedules an ack for terminal entries; we let it queue without
     * driving the scheduler.
     */
    private static class RecordingSender implements RewardClaimManager.ClaimRequestSender {
        final List<Object[]> sweeps = new ArrayList<>();
        final List<Object[]> polls = new ArrayList<>();
        final List<Object[]> acks = new ArrayList<>();

        @Override
        public void sendPoll(String eventId, String teakAppId, String clickingUserId,
            RewardClaimManager.ReplyHandler handler) {
            polls.add(new Object[] {eventId, teakAppId, clickingUserId, handler});
        }

        @Override
        public void sendAck(String eventId, String teakAppId, String clickingUserId,
            RewardClaimManager.ReplyHandler handler) {
            acks.add(new Object[] {eventId, teakAppId, clickingUserId, handler});
        }

        @Override
        public void sendSweep(String teakAppId, String clickingUserId,
            RewardClaimManager.ReplyHandler handler) {
            sweeps.add(new Object[] {teakAppId, clickingUserId, handler});
        }
    }
}
