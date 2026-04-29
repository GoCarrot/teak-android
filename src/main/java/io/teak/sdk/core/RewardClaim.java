package io.teak.sdk.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import io.teak.sdk.json.JSONObject;

/**
 * Per-claim state tracked by {@link RewardClaimManager} for a single click that the server
 * returned {@code claim_pending} for. Holds the originating session as a weak reference so
 * an Expiring→Active flicker (same Session instance after a brief background) keeps polling
 * alive, while a logout/login swap or post-Expired session change drops the entry on the
 * next stale-check site.
 *
 * <p>Attribution travels as the eleven-key flat-bag map (the canonical
 * {@code session_attribution} shape) so click-time and session-start-sweep entries share
 * one in-flight shape. Click-time callers flatten their launch-data once at start; sweep
 * callers unpack the wire blob.
 */
class RewardClaim {
    @NonNull
    final String eventId;

    /**
     * Reward id the click was originally fired against. May be null for legacy clicks.
     */
    @Nullable
    final String teakRewardId;

    /**
     * Session that fired the click. Used to detect cross-session staleness.
     */
    @NonNull
    final WeakReference<Session> originatingSession;

    /**
     * Snapshot of the originating session's user id at click time. The ack POST sends this
     * value even if the global session has rotated to a different user between attempts.
     */
    @NonNull
    final String originatingClickingUserId;

    /**
     * Eleven-key attribution map for this claim. Empty for direct-API claims with no
     * attribution context. Always non-null so the resolved-event merge has a stable shape.
     */
    @NonNull
    final Map<String, Object> attribution;

    int pollAttempt;
    int ackAttempt;

    /**
     * Set when a terminal (completed / failed) reply lands, before ack is fired.
     */
    @Nullable
    JSONObject resolvedReply;

    /**
     * Outstanding scheduled work for cancellation on session-Expired.
     */
    @Nullable
    ScheduledFuture<?> nextPollFuture;
    @Nullable
    ScheduledFuture<?> nextAckFuture;

    RewardClaim(@NonNull String eventId, @Nullable String teakRewardId,
        @NonNull Session originatingSession, @NonNull Map<String, Object> attribution) {
        this.eventId = eventId;
        this.teakRewardId = teakRewardId;
        this.originatingSession = new WeakReference<>(originatingSession);
        this.originatingClickingUserId = originatingSession.userId() == null ? "" : originatingSession.userId();
        this.attribution = attribution;
        this.pollAttempt = 0;
        this.ackAttempt = 0;
    }

    /**
     * True when the originating session has been collected or no longer matches the supplied
     * current session. An Expiring→Active flicker leaves the same {@link Session} instance
     * in place, so this returns false across that transition. A logout/login swap or a new
     * attributed launch creates a different Session instance, so this returns true and the
     * caller drops the claim.
     *
     * <p>If {@code currentSession} is null, the SDK has no current session to compare
     * against — typically only seen during early init. This is treated as "not stale" so
     * polling continues; the cancel-on-Expired path drops the entry when the session
     * actually ends.
     */
    boolean isStaleAgainstCurrentSession(@Nullable Session currentSession) {
        final Session origin = this.originatingSession.get();
        if (origin == null) return true;
        if (currentSession == null) return false;
        return origin != currentSession;
    }
}
