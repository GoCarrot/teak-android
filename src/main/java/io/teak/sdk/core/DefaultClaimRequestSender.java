package io.teak.sdk.core;

import androidx.annotation.NonNull;

/**
 * Production HTTP boundary for {@link RewardClaimManager}. Routes through
 * {@link io.teak.sdk.Request} so signing, mocking, and endpoint configuration carry over
 * from the rest of the SDK.
 *
 * <p>Both the poll ({@code GET /claim_status}) and the ack ({@code POST /claim_ack}) target
 * the same {@code rewards.gocarrot.com} host as the click POST. Per the taro spec, params
 * ride on the query string for the GET and in the body for the POST.
 */
class DefaultClaimRequestSender implements RewardClaimManager.ClaimRequestSender {
    static final DefaultClaimRequestSender INSTANCE = new DefaultClaimRequestSender();

    @Override
    public void sendPoll(@NonNull String eventId, @NonNull String teakAppId,
        @NonNull String clickingUserId, @NonNull RewardClaimManager.ReplyHandler handler) {
        // Implementation lands in the green commit.
    }

    @Override
    public void sendAck(@NonNull String eventId, @NonNull String teakAppId,
        @NonNull String clickingUserId, @NonNull RewardClaimManager.ReplyHandler handler) {
        // Implementation lands in the green commit.
    }
}
