package io.teak.sdk.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakConfiguration;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.event.SessionStateEvent;
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

    /** Status strings the manager treats as terminal on a /claim_status reply. */
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

    /** Test hook: number of in-flight claims at this moment. */
    public int inFlightCount() {
        synchronized (inFlightLock) {
            return inFlight.size();
        }
    }

    /** Test hook: clear all in-flight state without firing cancellation logic. */
    public void resetForTest() {
        synchronized (inFlightLock) {
            for (RewardClaim claim : inFlight.values()) {
                cancelFutures(claim);
            }
            inFlight.clear();
        }
    }

    /** Test hook: substitute the HTTP sender. */
    public void setSenderForTest(@Nullable ClaimRequestSender sender) {
        this.sender = sender;
    }

    /** Test hook: substitute the scheduler. */
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
     * Start polling for a claim that the server returned {@code claim_pending} for.
     * Idempotent: re-entrant calls for the same {@code eventId} while the entry is in flight
     * are dropped silently.
     */
    public void startPoll(@NonNull String eventId, @Nullable String teakRewardId,
        @NonNull Session originatingSession, @Nullable Teak.AttributedLaunchData launchData) {
        synchronized (inFlightLock) {
            if (inFlight.containsKey(eventId)) return;

            final RewardClaim claim = new RewardClaim(eventId, teakRewardId, originatingSession, launchData);
            inFlight.put(eventId, claim);
            schedulePoll(claim);
        }
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
            if (!inFlight.containsKey(claim.eventId)) return;
            final Session current = Session.getCurrentSessionOrNull();
            if (claim.isStaleAgainstCurrentSession(current)) {
                dropClaim(claim);
                return;
            }
        }

        final ClaimRequestSender s = effectiveSender();
        if (s == null) {
            // No sender wired (very early init); reschedule a single retry.
            schedulePoll(claim);
            return;
        }

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
            if (!inFlight.containsKey(claim.eventId)) return;
            final Session current = Session.getCurrentSessionOrNull();
            if (claim.isStaleAgainstCurrentSession(current)) {
                dropClaim(claim);
                return;
            }
        }

        // 5xx (or transport failure) on the poll: bump the attempt counter and try again
        // on the next backoff tick. Pending status without 5xx also reschedules.
        if (statusCode == 0 || (statusCode >= 500 && statusCode < 600)) {
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

            // Fire the resolved-event observer first, then start the ack POST. Order matters:
            // host games observe the reward grant before the SDK marks it acknowledged.
            if (claim.launchData != null) {
                Session.whenUserIdIsReadyPost(
                    new Teak.RewardClaimResolvedEvent(claim.launchData, claim.eventId, reply));
            }

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
            if (!inFlight.containsKey(claim.eventId)) return;
        }

        final ClaimRequestSender s = effectiveSender();
        if (s == null) {
            // Cannot ack without a sender; drop and rely on the session-start sweep on the
            // next launch to re-surface this claim.
            dropClaim(claim);
            return;
        }

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
        if (statusCode >= 200 && statusCode < 300) {
            dropClaim(claim);
            return;
        }

        // 5xx or transport failure: count the attempt and either retry or exhaust. Note:
        // ackAttempt is the number of attempts MADE, so after 3 attempts (initial + 2
        // retries) we drop. 4xx other than 2xx also exhausts immediately — only 5xx is
        // server-side-retriable.
        claim.ackAttempt++;
        final boolean isServerRetriable = statusCode == 0 || (statusCode >= 500 && statusCode < 600);
        if (!isServerRetriable || claim.ackAttempt >= ACK_MAX_ATTEMPTS) {
            // Drop. The session-start sweep on the next launch will re-surface unacked
            // terminal claims for at-least-once delivery.
            final Map<String, Object> data = new HashMap<>();
            data.put("event_id", claim.eventId);
            data.put("attempts", claim.ackAttempt);
            Teak.log.i("reward.claim.ack.exhausted", data);
            dropClaim(claim);
            return;
        }

        scheduleAck(claim);
    }

    private void dropClaim(@NonNull RewardClaim claim) {
        synchronized (inFlightLock) {
            cancelFutures(claim);
            inFlight.remove(claim.eventId);
        }
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

    /** Wired from {@link TeakCore} alongside the other static registrations. */
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
