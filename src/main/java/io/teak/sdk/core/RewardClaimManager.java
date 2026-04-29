package io.teak.sdk.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.event.SessionStateEvent;
import io.teak.sdk.json.JSONArray;
import io.teak.sdk.json.JSONObject;

/**
 * Owns the click-time poll-and-ack lifecycle for server JWT reward claims.
 *
 * <p>One instance per process, accessed via {@link #get()}. State per in-flight claim lives in
 * a {@link RewardClaim} object keyed on the server-issued {@code event_id}. Re-entrant
 * {@link #startPoll} for the same {@code event_id} is a no-op.
 *
 * <p>Polling cadence comes from {@code claim_poll_initial_delay_ms} and
 * {@code claim_poll_ceiling_ms} on the active {@link io.teak.sdk.configuration.RemoteConfiguration};
 * if settings is unreachable, hardcoded {@link #DEFAULT_INITIAL_DELAY_MS} /
 * {@link #DEFAULT_CEILING_MS} kick in. Schedule is
 * {@code min(initialMs * 2^attempt, ceilingMs)} on both poll and ack curves.
 *
 * <p>Cancellation: a {@link SessionStateEvent} transition to
 * {@link Session.State#Expired} drops every in-flight claim whose originating session is the
 * just-expired session. Expiring is intentionally not a cancellation point — an
 * Expiring→Active flicker leaves the same {@link Session} instance in place, so polling
 * continues across it. Per-claim stale checks at timer-fire and reply-landing protect
 * against the dictionary outliving a logout/login swap.
 */
public class RewardClaimManager {
    public static final int DEFAULT_INITIAL_DELAY_MS = 2000;
    public static final int DEFAULT_CEILING_MS = 30000;
    public static final int ACK_MAX_ATTEMPTS = 3;

    /**
     * Status strings the manager treats as terminal on a /claim_status reply.
     */
    static final String STATUS_COMPLETED = "completed";
    static final String STATUS_FAILED = "failed";
    static final String STATUS_PENDING = "pending";

    private static final RewardClaimManager INSTANCE = new RewardClaimManager();

    @NonNull
    public static RewardClaimManager get() {
        return INSTANCE;
    }

    /**
     * HTTP boundary the manager calls into. The default implementation routes through
     * {@link io.teak.sdk.Request}; tests substitute their own.
     */
    public interface ClaimRequestSender {
        void sendPoll(@NonNull String eventId, @NonNull String teakAppId,
            @NonNull String clickingUserId, @NonNull ReplyHandler handler);
        void sendAck(@NonNull String eventId, @NonNull String teakAppId,
            @NonNull String clickingUserId, @NonNull ReplyHandler handler);
        void sendSweep(@NonNull String teakAppId, @NonNull String clickingUserId,
            @NonNull ReplyHandler handler);
    }

    public interface ReplyHandler {
        void onReply(int statusCode, @Nullable String body);
    }

    /**
     * Test hook: returns a snapshot of currently-known event ids. Order is not stable.
     */
    @NonNull
    public List<String> inFlightEventIdsForTest() {
        synchronized (inFlightLock) {
            return new ArrayList<>(inFlight.keySet());
        }
    }

    /**
     * Test hook: number of in-flight claims at this moment.
     */
    public int inFlightCount() {
        synchronized (inFlightLock) {
            return inFlight.size();
        }
    }

    /**
     * Test hook: clear all in-flight state without firing cancellation logic.
     */
    public void resetForTest() {
        synchronized (inFlightLock) {
            for (RewardClaim claim : inFlight.values()) {
                cancelFutures(claim);
            }
            inFlight.clear();
        }
    }

    /**
     * Test hook: substitute the HTTP sender.
     */
    public void setSenderForTest(@Nullable ClaimRequestSender sender) {
        this.sender = sender;
    }

    /**
     * Test hook: substitute the scheduler.
     */
    public void setSchedulerForTest(@NonNull ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    private final Map<String, RewardClaim> inFlight = new HashMap<>();
    private final Object inFlightLock = new Object();

    @Nullable
    private ClaimRequestSender sender;

    @NonNull
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private RewardClaimManager() {}

    /**
     * Start polling for a claim that the server returned {@code claim_pending} for, with
     * launch-data attribution for the originating click. Convenience for the click-time
     * path: flattens {@code launchData} once into the eleven-key attribution map and
     * delegates to {@link #startPoll(String, String, Session, Map)}.
     *
     * <p>Idempotent: re-entrant calls for the same {@code eventId} while the entry is in
     * flight are dropped silently.
     */
    public void startPoll(@NonNull String eventId, @Nullable String teakRewardId,
        @NonNull Session originatingSession, @Nullable Teak.AttributedLaunchData launchData) {
        startPoll(eventId, teakRewardId, originatingSession, attributionMapFromLaunchData(launchData));
    }

    /**
     * Start polling with the eleven-key attribution map directly. Used by the session-start
     * sweep dispatcher, which sources attribution from the wire {@code session_attribution}
     * blob rather than a launch-data object.
     *
     * <p>Idempotent: re-entrant calls for the same {@code eventId} while the entry is in
     * flight are dropped silently. This is the dedupe site between click-time and sweep
     * paths.
     */
    public void startPoll(@NonNull String eventId, @Nullable String teakRewardId,
        @NonNull Session originatingSession, @NonNull Map<String, Object> attribution) {
        synchronized (inFlightLock) {
            if (inFlight.containsKey(eventId)) {
                Teak.log.i("claim_poll.start.duplicate", logEntry(eventId));
                return;
            }

            final RewardClaim claim = new RewardClaim(eventId, teakRewardId, originatingSession, attribution);
            inFlight.put(eventId, claim);
            schedulePoll(claim);
        }
    }

    @NonNull
    private static Map<String, Object> attributionMapFromLaunchData(
        @Nullable Teak.AttributedLaunchData launchData) {
        if (launchData == null) return Collections.emptyMap();
        return launchData.toMap();
    }

    /**
     * Drop polling for every claim whose originating session is the supplied session.
     * Called from the SessionStateEvent listener when a session moves to
     * {@link Session.State#Expired}.
     *
     * <p>Polling is session-bound — the resolved-event surfaces launch-data context tied to
     * a specific click, so a session that's gone makes the poll context stale. Acknowledgement
     * is delivery-confirmation, not session-bound: the claim's {@code originatingClickingUserId}
     * was snapshotted at click time and is sent by value. So if a claim has already advanced
     * past poll resolution (a terminal /claim_status reply landed and the resolved event fired),
     * the in-process ack retry budget is allowed to play out even after the session ends.
     * Anything that fails to ack within the budget falls through to the session-start sweep
     * for at-least-once delivery on the next launch.
     */
    public void cancelClaimsForSession(@NonNull Session expiredSession) {
        synchronized (inFlightLock) {
            final Iterator<Map.Entry<String, RewardClaim>> iter = inFlight.entrySet().iterator();
            while (iter.hasNext()) {
                final RewardClaim claim = iter.next().getValue();
                final Session origin = claim.originatingSession.get();
                if (origin != null && origin != expiredSession) continue;

                if (claim.nextPollFuture != null) {
                    claim.nextPollFuture.cancel(false);
                    claim.nextPollFuture = null;
                }

                if (claim.resolvedReply == null) {
                    // Still polling — no ack scheduled, so nothing to preserve. Drop the
                    // entry alongside its cancelled poll.
                    iter.remove();
                }
                // Otherwise: keep the entry alive so the in-flight ack future can complete.
                // onAckReply will dropClaim once it terminates (success or budget exhausted).
            }
        }
    }

    private void schedulePoll(@NonNull final RewardClaim claim) {
        final long delayMs = computeBackoffMs(claim.pollAttempt,
            currentInitialMs(), currentCeilingMs());
        claim.nextPollFuture = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                firePoll(claim);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void firePoll(@NonNull final RewardClaim claim) {
        // Stale-check site #1 (timer-fire / pre-send): catches the cancelled-and-cleared
        // dictionary case, where another path has already removed this claim.
        synchronized (inFlightLock) {
            if (!inFlight.containsKey(claim.eventId)) {
                Teak.log.i("claim_poll.timer.cancelled", logEntry(claim.eventId));
                return;
            }
            final Session current = Session.getCurrentSessionOrNull();
            if (claim.isStaleAgainstCurrentSession(current)) {
                dropClaim(claim, "session_stale");
                return;
            }
        }

        final ClaimRequestSender s = effectiveSender();
        if (s == null) {
            // No sender wired (very early init); reschedule a single retry.
            schedulePoll(claim);
            return;
        }

        Teak.log.i("claim_poll.request.send", logEntry(claim.eventId));
        final String teakAppId = currentAppId();
        s.sendPoll(claim.eventId, teakAppId, claim.originatingClickingUserId,
            new ReplyHandler() {
                @Override
                public void onReply(int statusCode, @Nullable String body) {
                    onPollReply(claim, statusCode, body);
                }
            });
    }

    private void onPollReply(@NonNull RewardClaim claim, int statusCode, @Nullable String body) {
        // Stale-check site #2 (reply-landing / post-send): catches "claim survived in dict
        // but the session was swapped during the request."
        synchronized (inFlightLock) {
            if (!inFlight.containsKey(claim.eventId)) {
                Teak.log.i("claim_poll.reply.cancelled", logEntry(claim.eventId));
                return;
            }
            final Session current = Session.getCurrentSessionOrNull();
            if (claim.isStaleAgainstCurrentSession(current)) {
                dropClaim(claim, "session_stale");
                return;
            }
        }

        // 5xx (or transport failure) on the poll: bump the attempt counter and try again
        // on the next backoff tick. Pending status without 5xx also reschedules.
        if (statusCode == 0 || (statusCode >= 500 && statusCode < 600)) {
            final Map<String, Object> data = logEntry(claim.eventId);
            data.put("status_code", statusCode);
            Teak.log.e("claim_poll.request.error", data);
            claim.pollAttempt++;
            schedulePoll(claim);
            return;
        }

        JSONObject reply = null;
        if (body != null) {
            try {
                reply = new JSONObject(body);
            } catch (Exception ignored) {
            }
        }

        final String status = reply == null ? null : reply.optString("status", null);

        if (STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status)) {
            claim.resolvedReply = reply;
            Teak.log.i("claim_resolved.received", logEntry(claim.eventId));

            // Fire the resolved-event observer first, then start the ack POST. Order matters:
            // host games observe the reward grant before the SDK marks it acknowledged.
            Session.whenUserIdIsReadyPost(
                new Teak.RewardClaimResolvedEvent(claim.attribution, claim.eventId, reply));
            Teak.log.i("claim_resolved.delivered", logEntry(claim.eventId));

            scheduleAck(claim);
            return;
        }

        // Pending or unknown: keep polling.
        claim.pollAttempt++;
        schedulePoll(claim);
    }

    private void scheduleAck(@NonNull final RewardClaim claim) {
        final long delayMs = claim.ackAttempt == 0
                                 ? 0L
                                 : computeBackoffMs(claim.ackAttempt - 1, currentInitialMs(), currentCeilingMs());
        claim.nextAckFuture = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                fireAck(claim);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void fireAck(@NonNull final RewardClaim claim) {
        // Defense-in-depth against TOCTOU on scheduleAck assignment outside the lock: if
        // the claim has been dropped from the dictionary between schedule and fire, no-op.
        synchronized (inFlightLock) {
            if (!inFlight.containsKey(claim.eventId)) {
                Teak.log.i("claim_ack.cancelled", logEntry(claim.eventId));
                return;
            }
        }

        final ClaimRequestSender s = effectiveSender();
        if (s == null) {
            // Cannot ack without a sender; drop and rely on the session-start sweep on the
            // next launch to re-surface this claim.
            dropClaim(claim, "no_sender");
            return;
        }

        Teak.log.i("claim_ack.request.send", logEntry(claim.eventId));
        final String teakAppId = currentAppId();
        s.sendAck(claim.eventId, teakAppId, claim.originatingClickingUserId,
            new ReplyHandler() {
                @Override
                public void onReply(int statusCode, @Nullable String body) {
                    onAckReply(claim, statusCode);
                }
            });
    }

    private void onAckReply(@NonNull RewardClaim claim, int statusCode) {
        final Map<String, Object> ackEntry = logEntry(claim.eventId);
        ackEntry.put("status_code", statusCode);
        Teak.log.i("claim_ack.request.reply", ackEntry);

        if (statusCode >= 200 && statusCode < 300) {
            // Success: remove the in-flight entry directly. (Not "dropped" — a drop log
            // implies the claim never made it; this one was delivered.)
            synchronized (inFlightLock) {
                cancelFutures(claim);
                inFlight.remove(claim.eventId);
            }
            return;
        }

        // Non-2xx: count the attempt and either retry or exhaust. Only 5xx and transport
        // failures (statusCode == 0) are server-side-retriable; 4xx other than 2xx
        // exhausts immediately. ackAttempt is the number of attempts MADE, so after 3
        // attempts (initial + 2 retries) we drop.
        Teak.log.e("claim_ack.request.error", ackEntry);
        claim.ackAttempt++;
        final boolean isServerRetriable = statusCode == 0 || (statusCode >= 500 && statusCode < 600);
        if (!isServerRetriable || claim.ackAttempt >= ACK_MAX_ATTEMPTS) {
            // Drop. The session-start sweep on the next launch will re-surface unacked
            // terminal claims for at-least-once delivery.
            final Map<String, Object> data = logEntry(claim.eventId);
            data.put("attempts", claim.ackAttempt);
            Teak.log.i("claim_ack.retry_exhausted", data);
            synchronized (inFlightLock) {
                cancelFutures(claim);
                inFlight.remove(claim.eventId);
            }
            return;
        }

        scheduleAck(claim);
    }

    private void dropClaim(@NonNull RewardClaim claim, @NonNull String reason) {
        final Map<String, Object> data = logEntry(claim.eventId);
        data.put("reason", reason);
        Teak.log.i("claim_poll.dropped", data);
        synchronized (inFlightLock) {
            cancelFutures(claim);
            inFlight.remove(claim.eventId);
        }
    }

    /**
     * Fire the session-start sweep against {@code GET /claims}. Reads the unacked-claims
     * list for the supplied session's user id and dispatches each entry through
     * {@link #dispatchSweptClaims}. A non-2xx or empty/unparseable body is treated as no
     * claims to surface this launch — the at-least-once contract on
     * {@link Teak.RewardClaimResolvedEvent} re-tries on the next session start.
     */
    public void startSweep(@NonNull final Session originatingSession) {
        final ClaimRequestSender s = effectiveSender();
        if (s == null) {
            Teak.log.i("claim_sweep.no_sender", new HashMap<String, Object>());
            return;
        }

        final String teakAppId = currentAppId();
        final String clickingUserId = originatingSession.userId() == null ? "" : originatingSession.userId();
        if (clickingUserId.isEmpty() || teakAppId.isEmpty()) {
            // No user / no app id → nothing to sweep against.
            return;
        }

        Teak.log.i("claim_sweep.request.send", new HashMap<>());
        s.sendSweep(teakAppId, clickingUserId, new ReplyHandler() {
            @Override
            public void onReply(int statusCode, @Nullable String body) {
                if (statusCode < 200 || statusCode >= 300 || body == null) {
                    final Map<String, Object> data = new HashMap<>();
                    data.put("status_code", statusCode);
                    Teak.log.i("claim_sweep.request.error", data);
                    return;
                }
                JSONArray claims = null;
                try {
                    final JSONObject reply = new JSONObject(body);
                    claims = reply.optJSONArray("claims");
                } catch (Exception ignored) {
                }
                if (claims == null) return;
                dispatchSweptClaims(claims, originatingSession);
            }
        });
    }

    /**
     * Per-claim dispatch for a sweep response. Wire-free seam used by both production
     * ({@link #startSweep}) and tests. Each entry must carry an {@code event_id} and
     * {@code status}; malformed entries are dropped with the rest of the list dispatched.
     *
     * <p>Branching:
     * <ul>
     *   <li>Terminal ({@code completed} / {@code failed}): enroll into the in-flight
     *       dictionary, fire {@link Teak.RewardClaimResolvedEvent}, schedule the ack.</li>
     *   <li>Pending: enroll into the in-flight dictionary and schedule the next poll. Does
     *       not fire {@link Teak.RewardClaimPendingEvent} — Pending is point-in-time.</li>
     * </ul>
     *
     * <p>Idempotency is the existing in-flight dictionary's {@code event_id} key — sweep
     * entries for an id already mid-poll, mid-request, or post-resolve awaiting ack are
     * no-ops via the dedupe guard in {@link #startPoll}.
     */
    public void dispatchSweptClaims(@NonNull JSONArray claims, @NonNull Session originatingSession) {
        for (int i = 0; i < claims.length(); i++) {
            final JSONObject entry = claims.optJSONObject(i);
            if (entry == null) continue;

            final String eventId = entry.optString("event_id", null);
            if (eventId == null || eventId.isEmpty()) continue;

            final String status = entry.optString("status", null);
            final boolean isTerminal =
                STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status);
            final boolean isPending = STATUS_PENDING.equals(status);
            if (!isTerminal && !isPending) continue;

            final Map<String, Object> attribution = unpackSessionAttribution(entry.opt("session_attribution"));
            final String teakRewardId = teakRewardIdFromAttribution(attribution);

            synchronized (inFlightLock) {
                if (inFlight.containsKey(eventId)) {
                    Teak.log.i("claim_sweep.dedupe", logEntry(eventId));
                    continue;
                }

                final RewardClaim claim = new RewardClaim(
                    eventId, teakRewardId, originatingSession, attribution);
                inFlight.put(eventId, claim);

                if (isTerminal) {
                    claim.resolvedReply = entry;
                    Teak.log.i("claim_sweep.resolved", logEntry(eventId));
                    Session.whenUserIdIsReadyPost(
                        new Teak.RewardClaimResolvedEvent(claim.attribution, eventId, entry));
                    scheduleAck(claim);
                } else {
                    Teak.log.i("claim_sweep.pending", logEntry(eventId));
                    schedulePoll(claim);
                }
            }
        }
    }

    /**
     * Decode the wire {@code session_attribution} field. The blob is opaque text on the
     * server (per the wire-format spec); the SDK accepts either an inline JSON object or
     * a JSON-encoded string for forward-compat with either taro encoding choice. Anything
     * else returns the empty map — attribution is best-effort context, not a gate on
     * dispatch.
     */
    @NonNull
    private static Map<String, Object> unpackSessionAttribution(@Nullable Object raw) {
        if (raw == null) return Collections.emptyMap();
        if (raw instanceof JSONObject) {
            return ((JSONObject) raw).toMap();
        }
        if (raw instanceof String) {
            final String s = (String) raw;
            if (s.isEmpty()) return Collections.emptyMap();
            try {
                return new JSONObject(s).toMap();
            } catch (Exception ignored) {
                return Collections.emptyMap();
            }
        }
        return Collections.emptyMap();
    }

    @Nullable
    private static String teakRewardIdFromAttribution(@NonNull Map<String, Object> attribution) {
        final Object v = attribution.get("teakRewardId");
        if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        return null;
    }

    @NonNull
    private static Map<String, Object> logEntry(@NonNull String eventId) {
        final Map<String, Object> data = new HashMap<>();
        data.put("event_id", eventId);
        return data;
    }

    private static void cancelFutures(@NonNull RewardClaim claim) {
        if (claim.nextPollFuture != null) {
            claim.nextPollFuture.cancel(false);
            claim.nextPollFuture = null;
        }
        if (claim.nextAckFuture != null) {
            claim.nextAckFuture.cancel(false);
            claim.nextAckFuture = null;
        }
    }

    private int currentInitialMs() {
        try {
            final TeakConfiguration tc = TeakConfiguration.get();
            if (tc.remoteConfiguration != null) {
                return tc.remoteConfiguration.claimPollInitialDelayMs;
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_INITIAL_DELAY_MS;
    }

    private int currentCeilingMs() {
        try {
            final TeakConfiguration tc = TeakConfiguration.get();
            if (tc.remoteConfiguration != null) {
                return tc.remoteConfiguration.claimPollCeilingMs;
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_CEILING_MS;
    }

    @NonNull
    private String currentAppId() {
        try {
            return TeakConfiguration.get().appConfiguration.appId;
        } catch (Exception ignored) {
            return "";
        }
    }

    @Nullable
    private ClaimRequestSender effectiveSender() {
        if (this.sender != null) return this.sender;
        return DefaultClaimRequestSender.INSTANCE;
    }

    /**
     * Backoff schedule shared by the poll and ack curves. Pure function so it can be
     * unit-tested in isolation. Returns {@code min(initialMs * 2^attempt, ceilingMs)}.
     */
    public static long computeBackoffMs(int attempt, int initialMs, int ceilingMs) {
        if (attempt < 0) attempt = 0;
        // Cap the shift to avoid overflow on pathological attempt counts.
        if (attempt > 30) return ceilingMs;
        long scaled = ((long) initialMs) << attempt;
        return Math.min(scaled, ceilingMs);
    }

    /**
     * Wired from {@link TeakCore} alongside the other static registrations.
     */
    public static void registerStaticEventListeners() {
        TeakEvent.addEventListener(event -> {
            if (!SessionStateEvent.Type.equals(event.eventType)) return;
            final SessionStateEvent stateEvent = (SessionStateEvent) event;
            if (stateEvent.state == Session.State.Expired) {
                INSTANCE.cancelClaimsForSession(stateEvent.session);
            }
        });
    }
}
