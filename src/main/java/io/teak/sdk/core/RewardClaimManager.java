package io.teak.sdk.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.teak.sdk.Teak;
import io.teak.sdk.TeakEvent;
import io.teak.sdk.event.SessionStateEvent;

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

    @NonNull
    public List<String> inFlightEventIdsForTest() {
        synchronized (inFlightLock) {
            return new ArrayList<>(inFlight.keySet());
        }
    }

    public int inFlightCount() {
        synchronized (inFlightLock) {
            return inFlight.size();
        }
    }

    public void resetForTest() {
        synchronized (inFlightLock) {
            inFlight.clear();
        }
    }

    public void setSenderForTest(@Nullable ClaimRequestSender sender) {
        this.sender = sender;
    }

    public void setSchedulerForTest(@NonNull java.util.concurrent.ScheduledExecutorService scheduler) {
        // Skeleton: scheduler hook lands in the implementation commit.
    }

    private final Map<String, RewardClaim> inFlight = new HashMap<>();
    private final Object inFlightLock = new Object();

    @Nullable
    private ClaimRequestSender sender;

    private RewardClaimManager() {}

    /**
     * Start polling for a claim that the server returned {@code claim_pending} for.
     * Idempotent: re-entrant calls for the same {@code eventId} while the entry is in flight
     * are dropped silently.
     */
    public void startPoll(@NonNull String eventId, @Nullable String teakRewardId,
        @NonNull Session originatingSession, @Nullable Teak.AttributedLaunchData launchData) {
        // Implementation lands in the green commit.
    }

    /**
     * Cancel and drop every claim whose originating session is the supplied session. Called
     * from the SessionStateEvent listener when a session moves to {@link Session.State#Expired}.
     */
    public void cancelClaimsForSession(@NonNull Session expiredSession) {
        // Implementation lands in the green commit.
    }

    /**
     * Backoff schedule shared by the poll and ack curves. Pure function so it can be
     * unit-tested in isolation. Returns {@code min(initialMs * 2^attempt, ceilingMs)}.
     */
    public static long computeBackoffMs(int attempt, int initialMs, int ceilingMs) {
        if (attempt < 0) attempt = 0;
        if (attempt > 30) return ceilingMs;
        long scaled = ((long) initialMs) << attempt;
        return Math.min(scaled, ceilingMs);
    }

    /** Wired from {@link TeakCore} alongside the other static registrations. */
    public static void registerStaticEventListeners() {
        // Implementation lands in the green commit.
    }
}
